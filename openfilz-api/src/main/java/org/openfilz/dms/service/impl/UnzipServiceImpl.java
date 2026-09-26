package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.openfilz.dms.config.UnzipProperties;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.UnzipRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.UnzipResponse;
import org.openfilz.dms.dto.response.UnzipResponse.SkipReason;
import org.openfilz.dms.dto.response.UnzipResponse.UnzipSkippedEntry;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.*;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.SaveDocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.UnzipService;
import org.openfilz.dms.service.unzip.SizeLimitedInputStream;
import org.openfilz.dms.service.unzip.ZipExtractionPlan;
import org.openfilz.dms.service.unzip.ZipExtractionPlan.FileEntry;
import org.openfilz.dms.utils.InputStreamFilePart;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Extracts a ZIP document server-side. Built for throughput on large archives:
 * <ul>
 *   <li>the archive is read once: opened in place when the storage is a local file, otherwise
 *       streamed once to a temporary file — never held in memory;</li>
 *   <li>everything is planned from the central directory first ({@link ZipExtractionPlan}): limits,
 *       unsafe paths and the user quota are checked once, before anything is written;</li>
 *   <li>folders are created level by level (siblings in parallel), then files are inflated and
 *       stored {@link UnzipProperties#getParallelism()} at a time straight from the archive —
 *       random access through {@link ZipFile}, known sizes, so MinIO gets a single PutObject per
 *       file with no intermediate copy;</li>
 *   <li>name clashes are resolved against one listing per pre-existing folder instead of one query
 *       per entry; folders created by the extraction need no check at all.</li>
 * </ul>
 * Files go through {@link SaveDocumentService} and folders through
 * {@link DocumentService#createFolder}, so each one is audited ({@code UPLOAD_DOCUMENT} /
 * {@code CREATE_FOLDER}), post-processed (index, thumbnail, …) and — in EE — owned, shared and
 * scanned like any other upload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UnzipServiceImpl implements UnzipService, UserInfoService {

    private static final String ZIP_EXTENSION = ".zip";
    private static final Set<String> ZIP_CONTENT_TYPES = Set.of(
            "application/zip", "application/x-zip-compressed", "application/x-zip", "multipart/x-zip");

    private final DocumentService documentService;
    private final DocumentDAO documentDAO;
    private final StorageService storageService;
    private final SaveDocumentService saveDocumentService;
    private final StorageQuotaService storageQuotaService;
    private final UnzipProperties props;

    /** A folder of the destination tree; {@code id == null} is the root. */
    private record FolderRef(UUID id, boolean preExisting) {
    }

    /** The opened archive, plus the temporary copy to delete afterwards (null when opened in place). */
    private record OpenedArchive(ZipFile zipFile, Path tempCopy) {
    }

    @Override
    public Mono<UnzipResponse> unzip(UUID zipId, UnzipRequest request) {
        boolean allowDuplicates = Boolean.TRUE.equals(request.allowDuplicateFileNames());
        return documentService.findDocumentToDownloadById(zipId)
                .map(this::requireZip)
                .flatMap(zip -> Mono.usingWhen(
                        openArchive(zip),
                        archive -> plan(archive)
                                .flatMap(plan -> validateUserQuota(plan.totalFileBytes())
                                        .then(resolveDestination(zip, request))
                                        .flatMap(destination -> new Extraction(archive, plan, allowDuplicates).run(destination, request.newFolderName()))),
                        this::close,
                        (archive, _) -> close(archive),
                        this::close));
    }

    private Document requireZip(Document document) {
        String name = document.getName() != null ? document.getName().toLowerCase(Locale.ROOT) : "";
        String contentType = document.getContentType() != null ? document.getContentType().toLowerCase(Locale.ROOT) : "";
        if (document.getType() != DocumentType.FILE || !(name.endsWith(ZIP_EXTENSION) || ZIP_CONTENT_TYPES.contains(contentType))) {
            throw new UnzipException(UnzipException.NOT_A_ZIP, "'" + document.getName() + "' is not a ZIP archive");
        }
        return document;
    }

    // ---------------------------------------------------------------- archive

    private Mono<OpenedArchive> openArchive(Document zip) {
        return storageService.loadFile(zip.getStoragePath())
                .flatMap(resource -> Mono.fromCallable(() -> open(resource, zip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private OpenedArchive open(Resource resource, Document zip) throws IOException {
        Path source = localFile(resource);
        Path tempCopy = null;
        if (source == null) {
            tempCopy = Files.createTempFile("openfilz-unzip-", ZIP_EXTENSION);
            try (InputStream in = resource.getInputStream()) {
                Files.copy(in, tempCopy, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException e) {
                Files.deleteIfExists(tempCopy);
                throw e;
            }
            source = tempCopy;
        }
        try {
            return new OpenedArchive(ZipFile.builder().setPath(source).get(), tempCopy);
        } catch (IOException e) {
            if (tempCopy != null) {
                Files.deleteIfExists(tempCopy);
            }
            throw new UnzipException(UnzipException.ZIP_INVALID, "'" + zip.getName() + "' is not a readable ZIP archive");
        }
    }

    /** The resource's file when the storage is local (no copy needed), else null. */
    private static Path localFile(Resource resource) {
        if (!resource.isFile()) {
            return null;
        }
        try {
            return resource.getFile().toPath();
        } catch (IOException | UnsupportedOperationException e) {
            return null;
        }
    }

    private Mono<Void> close(OpenedArchive archive) {
        return Mono.<Void>fromRunnable(() -> {
            try {
                archive.zipFile().close();
            } catch (IOException e) {
                log.warn("Could not close ZIP archive: {}", e.getMessage());
            }
            if (archive.tempCopy() != null) {
                try {
                    Files.deleteIfExists(archive.tempCopy());
                } catch (IOException e) {
                    log.warn("Could not delete temporary archive {}: {}", archive.tempCopy(), e.getMessage());
                }
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<ZipExtractionPlan> plan(OpenedArchive archive) {
        return Mono.fromCallable(() -> ZipExtractionPlan.of(() -> archive.zipFile().getEntries().asIterator(),
                        archive.zipFile()::canReadEntryData, props))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // ---------------------------------------------------------------- checks

    /** One quota check for the whole archive instead of one aggregate query per file. */
    private Mono<Void> validateUserQuota(long totalBytes) {
        return storageQuotaService.checkStorage(totalBytes);
    }

    private Mono<FolderRef> resolveDestination(Document zip, UnzipRequest request) {
        UUID target = Boolean.TRUE.equals(request.targetRoot()) ? null
                : request.targetFolderId() != null ? request.targetFolderId() : zip.getParentId();
        if (target == null) {
            return Mono.just(new FolderRef(null, true));
        }
        return documentDAO.existsByIdAndType(target, DocumentType.FOLDER, AccessType.RW)
                .flatMap(exists -> exists
                        ? Mono.just(new FolderRef(target, true))
                        : Mono.error(new DocumentNotFoundException(DocumentType.FOLDER, target)));
    }

    // ---------------------------------------------------------------- extraction

    /** State of one extraction run. */
    private final class Extraction {

        private final OpenedArchive archive;
        private final ZipExtractionPlan plan;
        private final boolean allowDuplicates;
        private final Map<String, FolderRef> folders = new ConcurrentHashMap<>();
        private final Map<String, Mono<Map<String, FolderElementInfo>>> listings = new ConcurrentHashMap<>();
        private final Queue<UnzipSkippedEntry> skipped = new ConcurrentLinkedQueue<>();
        private final AtomicInteger foldersCreated = new AtomicInteger();
        private final AtomicInteger filesExtracted = new AtomicInteger();

        private Extraction(OpenedArchive archive, ZipExtractionPlan plan, boolean allowDuplicates) {
            this.archive = archive;
            this.plan = plan;
            this.allowDuplicates = allowDuplicates;
            this.skipped.addAll(plan.skipped());
        }

        Mono<UnzipResponse> run(FolderRef destination, String newFolderName) {
            Mono<FolderRef> root = newFolderName == null || newFolderName.isBlank()
                    ? Mono.just(destination)
                    : documentService.createFolder(new CreateFolderRequest(newFolderName.strip(), destination.id()))
                            .map(created -> {
                                foldersCreated.incrementAndGet();
                                return new FolderRef(created.id(), false);
                            });
            return root.flatMap(rootRef -> {
                folders.put(ZipExtractionPlan.ROOT, rootRef);
                return Flux.fromIterable(plan.foldersByDepth())
                        .concatMap(level -> Flux.fromIterable(level)
                                .flatMap(this::ensureFolder, props.getParallelism())
                                .then())
                        .thenMany(Flux.fromIterable(plan.files())
                                .flatMap(this::extractFile, props.getParallelism()))
                        .then(Mono.fromCallable(() -> response(destination, rootRef)));
            });
        }

        private UnzipResponse response(FolderRef destination, FolderRef root) {
            List<UnzipSkippedEntry> skippedList = new ArrayList<>(skipped);
            skippedList.sort(Comparator.comparing(UnzipSkippedEntry::path));
            UUID createdFolderId = root.preExisting() ? null : root.id();
            return new UnzipResponse(destination.id(), createdFolderId, foldersCreated.get(), filesExtracted.get(), skippedList);
        }

        /** Creates the folder at {@code path}, or reuses an existing one of the same name. */
        private Mono<Void> ensureFolder(String path) {
            FolderRef parent = folders.get(ZipExtractionPlan.parentOf(path));
            if (parent == null) {
                skipped.add(new UnzipSkippedEntry(path, SkipReason.PARENT_NOT_CREATED, null));
                return Mono.empty();
            }
            String name = ZipExtractionPlan.nameOf(path);
            Mono<FolderRef> folder = !parent.preExisting()
                    ? createFolder(name, parent)
                    : existingChildren(parent).flatMap(children -> {
                        FolderElementInfo existing = children.get(name);
                        if (existing == null) {
                            return createFolder(name, parent);
                        }
                        if (existing.type() != DocumentType.FOLDER) {
                            return Mono.error(new DuplicateNameException(DocumentType.FILE, name));
                        }
                        // Merge into the existing folder, provided the user may write into it.
                        return documentDAO.existsByIdAndType(existing.id(), DocumentType.FOLDER, AccessType.RW)
                                .flatMap(writable -> writable
                                        ? Mono.just(new FolderRef(existing.id(), true))
                                        : Mono.error(new DocumentNotFoundException(DocumentType.FOLDER, existing.id())));
                    });
            return folder
                    .doOnNext(ref -> folders.put(path, ref))
                    .onErrorResume(e -> {
                        skipped.add(new UnzipSkippedEntry(path, reasonOf(e), e.getMessage()));
                        return Mono.empty();
                    })
                    .then();
        }

        private Mono<FolderRef> createFolder(String name, FolderRef parent) {
            return documentService.createFolder(new CreateFolderRequest(name, parent.id()))
                    .map(created -> {
                        foldersCreated.incrementAndGet();
                        return new FolderRef(created.id(), false);
                    });
        }

        private Mono<Void> extractFile(FileEntry file) {
            FolderRef parent = folders.get(file.parentPath());
            if (parent == null) {
                skipped.add(new UnzipSkippedEntry(file.path(), SkipReason.PARENT_NOT_CREATED, null));
                return Mono.empty();
            }
            Long maxFileSize = storageQuotaService.maxFileSizeBytes();
            if (maxFileSize != null && file.size() > maxFileSize) {
                skipped.add(new UnzipSkippedEntry(file.path(), SkipReason.FILE_TOO_LARGE, null));
                return Mono.empty();
            }
            Mono<Boolean> clash = allowDuplicates || !parent.preExisting()
                    ? Mono.just(false)
                    : existingChildren(parent).map(children -> children.containsKey(file.name()));
            return clash.flatMap(exists -> {
                        if (exists) {
                            skipped.add(new UnzipSkippedEntry(file.path(), SkipReason.DUPLICATE_NAME, null));
                            return Mono.empty();
                        }
                        return store(file, parent);
                    })
                    .onErrorResume(e -> {
                        log.debug("Unzip: entry '{}' not extracted: {}", file.path(), e.getMessage());
                        skipped.add(new UnzipSkippedEntry(file.path(), reasonOf(e), e.getMessage()));
                        return Mono.empty();
                    });
        }

        private Mono<Void> store(FileEntry file, FolderRef parent) {
            ZipFile zipFile = archive.zipFile();
            InputStreamFilePart part = new InputStreamFilePart(file.name(), file.size(),
                    () -> new SizeLimitedInputStream(zipFile.getInputStream(file.entry()), file.size(), file.path()));
            // Quotas were checked for the whole archive: passing the length skips the per-file re-check.
            return saveDocumentService.doSaveFile(part, file.size(), parent.id(), null, file.name(), storageService.saveFile(part))
                    .doOnNext(_ -> filesExtracted.incrementAndGet())
                    .then();
        }

        /** Names already used in a folder that existed before the extraction — listed once, then cached. */
        private Mono<Map<String, FolderElementInfo>> existingChildren(FolderRef folder) {
            String key = String.valueOf(folder.id());
            return listings.computeIfAbsent(key, _ -> documentDAO.listDocumentInfoInFolder(folder.id(), null)
                    // A folder beats a file of the same name, so that it can be merged into.
                    .collect(HashMap<String, FolderElementInfo>::new, (names, info) -> names.merge(info.name(), info,
                            (a, b) -> a.type() == DocumentType.FOLDER ? a : b))
                    .map(names -> (Map<String, FolderElementInfo>) names)
                    .cache());
        }
    }

    private static SkipReason reasonOf(Throwable e) {
        if (e instanceof DuplicateNameException) {
            return SkipReason.DUPLICATE_NAME;
        }
        if (e instanceof FileSizeExceededException) {
            return SkipReason.FILE_TOO_LARGE;
        }
        return SkipReason.ERROR;
    }

}
