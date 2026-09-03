package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.PdfToolsProperties;
import org.openfilz.dms.dto.audit.PdfTransformAudit;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.pdf.MergeRequest;
import org.openfilz.dms.dto.request.pdf.MergeSource;
import org.openfilz.dms.dto.request.pdf.OrganizeRequest;
import org.openfilz.dms.dto.request.pdf.OutputMode;
import org.openfilz.dms.dto.request.pdf.OutputTarget;
import org.openfilz.dms.dto.request.pdf.PageInstruction;
import org.openfilz.dms.dto.request.pdf.RotateRequest;
import org.openfilz.dms.dto.request.pdf.SplitMode;
import org.openfilz.dms.dto.request.pdf.SplitOutput;
import org.openfilz.dms.dto.request.pdf.SplitRequest;
import org.openfilz.dms.dto.response.pdf.PdfInfo;
import org.openfilz.dms.dto.response.pdf.PdfOperationResponse;
import org.openfilz.dms.dto.response.pdf.PdfOutlineEntry;
import org.openfilz.dms.dto.response.pdf.PdfOutputInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.PdfToolsException;
import org.openfilz.dms.repository.SignatureEnvelopeRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.PdfToolsService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.pdf.PageRangeParser;
import org.openfilz.dms.service.pdf.PdfCompositionEngine;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.Inspection;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.OutlineSpec;
import org.openfilz.dms.service.pdf.PdfCompositionEngine.PageRef;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.PathFilePart;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Orchestrates the PDF tools around {@link PdfCompositionEngine}: resolves and downloads the sources
 * with the caller's read access, applies the deployment limits, runs the composition on the
 * bounded-elastic scheduler under a small concurrency budget, and writes the result back through
 * the regular document pipelines — {@code uploadDocument} for a new document,
 * {@code replaceDocumentContent} for a new version — so ownership, audit, checksum, quota,
 * thumbnails and indexing apply exactly as for a user upload. Every output additionally gets a
 * {@code PDF_TRANSFORM} audit entry carrying its provenance.
 * <p>
 * In-place edits are refused on password-protected PDFs, on signed PDFs unless the caller
 * acknowledges the signature loss, on documents attached to an active e-Sign envelope, and under
 * WORM mode (where only new documents may be written).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PdfToolsServiceImpl implements PdfToolsService {

    static final String PDF_MIME = "application/pdf";
    static final String PDF_EXTENSION = ".pdf";
    static final String DEFAULT_SPLIT_PATTERN = "{name}-{index}";

    private final DocumentService documentService;
    private final StorageService storageService;
    private final PdfCompositionEngine engine;
    private final PdfToolsProperties props;
    private final AuditService auditService;
    private final SignatureEnvelopeRepository envelopeRepository;

    @Value("${openfilz.security.worm-mode:false}")
    private boolean wormMode;

    private Semaphore slots;

    @PostConstruct
    void init() {
        slots = new Semaphore(Math.max(1, props.getMaxConcurrentOperations()), true);
    }

    /** A source PDF, downloaded to the operation's workspace and inspected. */
    record Source(Document document, Path file, Inspection inspection) {
        UUID id() {
            return document.getId();
        }

        int pageCount() {
            return inspection.pageCount();
        }
    }

    /** One part of a split: its pages and, for outline splits, the bookmark title. */
    record Part(List<Integer> pages, String title) {
    }

    // ── info ────────────────────────────────────────────────────────────────

    @Override
    public Mono<PdfInfo> info(UUID documentId) {
        return withWorkspace(dir -> loadSources(List.of(documentId), dir)
                .flatMap(sources -> {
                    Source s = sources.getFirst();
                    Inspection i = s.inspection();
                    return hasActiveEnvelope(s.id())
                            .map(activeEnvelope -> new PdfInfo(s.id(), s.document().getName(), sizeOf(s.document()),
                                    i.pageCount(), i.pages(), i.encrypted(), i.signed(), activeEnvelope, i.outline()));
                }));
    }

    /**
     * True while at least one non-terminal e-Sign envelope references this document: replacing its
     * content would pull the ground from under an in-flight signing round, so in-place saves are
     * refused and callers are told up front to target a new document.
     */
    private Mono<Boolean> hasActiveEnvelope(UUID documentId) {
        return envelopeRepository.findBySourceDocId(documentId)
                .filter(envelope -> envelope.getStatus() != null && !envelope.getStatus().isTerminal())
                .hasElements();
    }

    // ── merge ───────────────────────────────────────────────────────────────

    @Override
    public Mono<PdfOperationResponse> merge(MergeRequest request) {
        List<MergeSource> inputs = request.sources();
        if (inputs == null || inputs.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one source document is required"));
        }
        if (inputs.stream().anyMatch(s -> s == null || s.documentId() == null)) {
            return Mono.error(new IllegalArgumentException("Every source needs a documentId"));
        }
        OutputTarget output = request.output() != null ? request.output() : OutputTarget.defaults();
        boolean addOutline = Boolean.TRUE.equals(request.addOutline());
        List<UUID> ids = inputs.stream().map(MergeSource::documentId).distinct().toList();

        return withWorkspace(dir -> loadSources(ids, dir).flatMap(sources -> {
            Map<UUID, Source> byId = index(sources);
            requireTransformable(sources);
            List<PageRef> refs = new ArrayList<>();
            List<OutlineSpec> outline = new ArrayList<>();
            for (MergeSource input : inputs) {
                Source src = byId.get(input.documentId());
                List<Integer> pages = PageRangeParser.parse(input.pages(), src.pageCount());
                if (addOutline) {
                    outline.add(new OutlineSpec(stripExtension(src.document().getName()), refs.size() + 1));
                }
                for (int page : pages) {
                    refs.add(new PageRef(src.file(), page, 0));
                }
            }
            requirePageBudget(refs.size());
            Source first = byId.get(inputs.getFirst().documentId());
            String name = outputName(output.name(), stripExtension(first.document().getName()) + " (merged)");
            return compose(dir, refs, outline, stripExtension(name))
                    .flatMap(out -> write("merge", out, refs.size(), output, OutputMode.NEW_DOCUMENT, first, name, ids));
        })).map(info -> new PdfOperationResponse("merge", List.of(info)));
    }

    // ── organize ────────────────────────────────────────────────────────────

    @Override
    public Mono<PdfOperationResponse> organize(OrganizeRequest request) {
        if (request.documentId() == null) {
            return Mono.error(new IllegalArgumentException("documentId is required"));
        }
        List<PageInstruction> instructions = request.pages();
        if (instructions == null || instructions.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one page is required"));
        }
        for (PageInstruction instruction : instructions) {
            if (instruction == null) {
                return Mono.error(new IllegalArgumentException("Page instructions must not be null"));
            }
            if (!PdfCompositionEngine.isRightAngle(instruction.rotationOrZero())) {
                return Mono.error(new IllegalArgumentException("Rotation must be a multiple of 90 degrees, got "
                        + instruction.rotation()));
            }
        }
        OutputTarget output = request.output() != null ? request.output() : OutputTarget.defaults();
        UUID mainId = request.documentId();
        List<UUID> ids = Stream.concat(Stream.of(mainId),
                        instructions.stream().map(PageInstruction::documentId).filter(id -> id != null && !id.equals(mainId)))
                .distinct().toList();

        return withWorkspace(dir -> loadSources(ids, dir).flatMap(sources -> {
            Map<UUID, Source> byId = index(sources);
            requireTransformable(sources);
            List<PageRef> refs = new ArrayList<>(instructions.size());
            for (PageInstruction instruction : instructions) {
                Source src = byId.get(instruction.documentId() != null ? instruction.documentId() : mainId);
                if (instruction.page() < 1 || instruction.page() > src.pageCount()) {
                    throw new IllegalArgumentException("Page " + instruction.page() + " is out of range for '"
                            + src.document().getName() + "' (" + src.pageCount() + " pages)");
                }
                refs.add(new PageRef(src.file(), instruction.page(),
                        PdfCompositionEngine.normalizeRotation(instruction.rotationOrZero())));
            }
            requirePageBudget(refs.size());
            Source main = byId.get(mainId);
            String name = outputName(output.name(), stripExtension(main.document().getName()) + " (edited)");
            return compose(dir, refs, preservedOutline(main, refs), stripExtension(name))
                    .flatMap(out -> write("organize", out, refs.size(), output, OutputMode.NEW_VERSION, main, name, ids));
        })).map(info -> new PdfOperationResponse("organize", List.of(info)));
    }

    // ── rotate ──────────────────────────────────────────────────────────────

    @Override
    public Mono<PdfOperationResponse> rotate(RotateRequest request) {
        List<UUID> ids = request.documentIds() == null ? List.of()
                : request.documentIds().stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one document is required"));
        }
        if (request.angle() == null || !PdfCompositionEngine.isRightAngle(request.angle())
                || PdfCompositionEngine.normalizeRotation(request.angle()) == 0) {
            return Mono.error(new IllegalArgumentException("angle must be 90, 180 or 270 degrees"));
        }
        int angle = PdfCompositionEngine.normalizeRotation(request.angle());
        OutputTarget output = request.output() != null ? request.output() : OutputTarget.defaults();
        boolean batch = ids.size() > 1;

        return withWorkspace(dir -> loadSources(ids, dir).flatMapMany(sources -> {
            requireTransformable(sources);
            requirePageBudget(sources.stream().mapToInt(Source::pageCount).sum());
            return Flux.fromIterable(sources).concatMap(src -> {
                Set<Integer> selected = new TreeSet<>(PageRangeParser.parse(request.pages(), src.pageCount()));
                List<PageRef> refs = new ArrayList<>(src.pageCount());
                for (int page = 1; page <= src.pageCount(); page++) {
                    refs.add(new PageRef(src.file(), page, selected.contains(page) ? angle : 0));
                }
                String name = outputName(batch ? null : output.name(),
                        stripExtension(src.document().getName()) + " (rotated)");
                return compose(dir, refs, preservedOutline(src, refs), stripExtension(name))
                        .flatMap(out -> write("rotate", out, refs.size(), output, OutputMode.NEW_VERSION, src, name,
                                List.of(src.id())));
            });
        }).collectList()).map(outputs -> new PdfOperationResponse("rotate", outputs));
    }

    // ── split ───────────────────────────────────────────────────────────────

    @Override
    public Mono<PdfOperationResponse> split(SplitRequest request) {
        if (request.documentId() == null || request.mode() == null) {
            return Mono.error(new IllegalArgumentException("documentId and mode are required"));
        }
        SplitOutput output = request.output() != null ? request.output() : SplitOutput.defaults();
        String pattern = output.namePattern() == null || output.namePattern().isBlank()
                ? DEFAULT_SPLIT_PATTERN : output.namePattern();

        return withWorkspace(dir -> loadSources(List.of(request.documentId()), dir).flatMap(sources -> {
            Source src = sources.getFirst();
            requireTransformable(sources);
            List<Part> parts = computeParts(request, src);
            if (parts.size() > props.getMaxOutputs()) {
                throw new PdfToolsException(PdfToolsException.TOO_MANY_OUTPUTS, "This split would produce "
                        + parts.size() + " documents; the limit is " + props.getMaxOutputs());
            }
            requirePageBudget(parts.stream().mapToInt(p -> p.pages().size()).sum());
            String baseName = stripExtension(src.document().getName());
            UUID folderId = output.folderId() != null ? output.folderId() : src.document().getParentId();
            // Optional because the target may legitimately be the root folder (null id).
            Mono<Optional<UUID>> targetFolder = Boolean.TRUE.equals(output.createSubfolder())
                    ? documentService.createFolder(new CreateFolderRequest(sanitizeName(baseName), folderId)).map(f -> Optional.of(f.id()))
                    : Mono.just(Optional.ofNullable(folderId));
            int width = String.valueOf(parts.size()).length();
            boolean allowDuplicates = Boolean.TRUE.equals(output.allowDuplicateFileNames());

            return targetFolder.flatMapMany(folder ->
                    Flux.range(0, parts.size()).concatMap(i -> {
                        Part part = parts.get(i);
                        List<PageRef> refs = part.pages().stream().map(p -> new PageRef(src.file(), p, 0)).toList();
                        String name = partName(pattern, baseName, i + 1, width, part);
                        return compose(dir, refs, preservedOutline(src, refs), stripExtension(name))
                                .flatMap(out -> writeNewDocument(out, name, folder.orElse(null), allowDuplicates, refs.size()))
                                .flatMap(info -> audit("split", info, List.of(src.id())));
                    })).collectList();
        })).map(outputs -> new PdfOperationResponse("split", outputs));
    }

    private List<Part> computeParts(SplitRequest request, Source src) {
        int pageCount = src.pageCount();
        SplitMode mode = request.mode();
        List<Part> parts = new ArrayList<>();
        switch (mode) {
            case EVERY_PAGE, EVERY_N_PAGES -> {
                int n = mode == SplitMode.EVERY_PAGE ? 1 : (request.n() != null ? request.n() : 0);
                if (n < 1) {
                    throw new IllegalArgumentException("n (pages per part) must be at least 1");
                }
                for (int start = 1; start <= pageCount; start += n) {
                    parts.add(new Part(range(start, Math.min(start + n - 1, pageCount)), null));
                }
            }
            case AT_PAGES -> {
                if (request.pages() == null || request.pages().isEmpty()) {
                    throw new IllegalArgumentException("pages (where new parts start) is required for AT_PAGES");
                }
                TreeSet<Integer> cuts = new TreeSet<>();
                for (Integer p : request.pages()) {
                    if (p == null || p < 2 || p > pageCount) {
                        throw new IllegalArgumentException("A cut page must be between 2 and " + pageCount + ", got " + p);
                    }
                    cuts.add(p);
                }
                int start = 1;
                for (int cut : cuts) {
                    parts.add(new Part(range(start, cut - 1), null));
                    start = cut;
                }
                parts.add(new Part(range(start, pageCount), null));
            }
            case PAGE_RANGES -> {
                if (request.ranges() == null || request.ranges().isEmpty()) {
                    throw new IllegalArgumentException("ranges is required for PAGE_RANGES");
                }
                for (String range : request.ranges()) {
                    parts.add(new Part(PageRangeParser.parse(range, pageCount), null));
                }
            }
            case BY_OUTLINE_LEVEL -> {
                int level = request.outlineLevel() != null ? request.outlineLevel() : 1;
                if (level < 1) {
                    throw new IllegalArgumentException("outlineLevel must be at least 1");
                }
                // First bookmark per start page, in page order; a leading unnamed part covers pages before the first one.
                Map<Integer, String> starts = new LinkedHashMap<>();
                src.inspection().outline().stream()
                        .filter(e -> e.level() <= level && e.page() != null)
                        .sorted(Comparator.comparingInt(PdfOutlineEntry::page))
                        .forEach(e -> starts.putIfAbsent(e.page(), e.title()));
                if (starts.isEmpty()) {
                    throw new PdfToolsException(PdfToolsException.PDF_NO_OUTLINE,
                            "'" + src.document().getName() + "' has no bookmarks of level " + level + " or above");
                }
                List<Integer> startPages = new ArrayList<>(starts.keySet());
                if (startPages.getFirst() > 1) {
                    parts.add(new Part(range(1, startPages.getFirst() - 1), null));
                }
                for (int i = 0; i < startPages.size(); i++) {
                    int from = startPages.get(i);
                    int to = i + 1 < startPages.size() ? startPages.get(i + 1) - 1 : pageCount;
                    parts.add(new Part(range(from, to), starts.get(from)));
                }
            }
        }
        return parts;
    }

    /**
     * The source's top-level bookmarks, re-pointed at the first output page that shows the page
     * they target; bookmarks whose page was dropped disappear. Keeps chapters navigable after a
     * reorder, a rotation or a split (nested levels are flattened away — the engine writes one level).
     */
    static List<OutlineSpec> preservedOutline(Source src, List<PageRef> refs) {
        Map<Integer, Integer> firstOutputPage = new HashMap<>();
        for (int i = 0; i < refs.size(); i++) {
            PageRef ref = refs.get(i);
            if (ref.source().equals(src.file())) {
                firstOutputPage.putIfAbsent(ref.page(), i + 1);
            }
        }
        List<OutlineSpec> outline = new ArrayList<>();
        for (PdfOutlineEntry entry : src.inspection().outline()) {
            if (entry.level() != 1 || entry.page() == null) {
                continue;
            }
            Integer target = firstOutputPage.get(entry.page());
            if (target != null) {
                outline.add(new OutlineSpec(entry.title(), target));
            }
        }
        // Bookmarks follow the output's page order, whatever the source order was.
        outline.sort(Comparator.comparingInt(OutlineSpec::firstPage));
        return outline;
    }

    private static List<Integer> range(int from, int to) {
        List<Integer> pages = new ArrayList<>(Math.max(0, to - from + 1));
        for (int p = from; p <= to; p++) {
            pages.add(p);
        }
        return pages;
    }

    static String partName(String pattern, String baseName, int index, int width, Part part) {
        String title = part.title() != null && !part.title().isBlank() ? part.title() : String.valueOf(index);
        String name = pattern
                .replace("{name}", baseName)
                .replace("{index}", String.format("%0" + width + "d", index))
                .replace("{first}", String.valueOf(part.pages().getFirst()))
                .replace("{last}", String.valueOf(part.pages().getLast()))
                .replace("{title}", title);
        return outputName(name, baseName + "-" + index);
    }

    // ── sources ─────────────────────────────────────────────────────────────

    private Mono<List<Source>> loadSources(List<UUID> ids, Path dir) {
        return Flux.fromIterable(ids)
                .concatMap(id -> documentService.findDocumentToDownloadById(id).map(this::requirePdf))
                .collectList()
                .flatMap(documents -> {
                    long total = 0;
                    for (Document d : documents) {
                        long size = sizeOf(d);
                        if (size > props.getMaxInputBytes()) {
                            return Mono.error(new FileSizeExceededException(d.getName(), size, props.getMaxInputBytes()));
                        }
                        total += size;
                    }
                    if (total > props.getMaxInputBytes()) {
                        return Mono.error(new FileSizeExceededException(total, props.getMaxInputBytes()));
                    }
                    return Flux.fromIterable(documents).concatMap(d -> download(d, dir)).collectList();
                });
    }

    private Mono<Source> download(Document document, Path dir) {
        return storageService.loadFile(document.getStoragePath())
                .flatMap(resource -> Mono.fromCallable(() -> {
                    Path file = dir.resolve("src-" + document.getId() + PDF_EXTENSION);
                    try (InputStream in = resource.getInputStream()) {
                        Files.copy(in, file);
                    }
                    try {
                        return new Source(document, file, engine.inspect(file));
                    } catch (IOException e) {
                        throw new PdfToolsException(PdfToolsException.PDF_INVALID,
                                "'" + document.getName() + "' is not a readable PDF: " + e.getMessage());
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    private Document requirePdf(Document document) {
        boolean pdf = document.getType() == DocumentType.FILE
                && (PDF_MIME.equalsIgnoreCase(document.getContentType())
                || (document.getName() != null && document.getName().toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION)));
        if (!pdf) {
            throw new PdfToolsException(PdfToolsException.NOT_A_PDF, "'" + document.getName() + "' is not a PDF document");
        }
        return document;
    }

    private static void requireTransformable(List<Source> sources) {
        for (Source s : sources) {
            if (s.inspection().encrypted()) {
                throw new PdfToolsException(PdfToolsException.PDF_ENCRYPTED,
                        "'" + s.document().getName() + "' is password-protected; remove the password first");
            }
            if (s.pageCount() < 1) {
                throw new PdfToolsException(PdfToolsException.PDF_INVALID, "'" + s.document().getName() + "' has no pages");
            }
        }
    }

    private void requirePageBudget(int pages) {
        if (pages > props.getMaxPages()) {
            throw new PdfToolsException(PdfToolsException.PDF_TOO_MANY_PAGES, "This operation covers " + pages
                    + " pages; the limit is " + props.getMaxPages());
        }
    }

    private static Map<UUID, Source> index(List<Source> sources) {
        Map<UUID, Source> byId = new HashMap<>();
        for (Source s : sources) {
            byId.put(s.id(), s);
        }
        return byId;
    }

    private static long sizeOf(Document d) {
        return d.getSize() != null ? d.getSize() : 0L;
    }

    // ── composition ─────────────────────────────────────────────────────────

    private Mono<Path> compose(Path dir, List<PageRef> refs, List<OutlineSpec> outline, String title) {
        return Mono.fromCallable(() -> {
            if (!slots.tryAcquire(Math.max(1, props.getSlotWaitSeconds()), TimeUnit.SECONDS)) {
                throw new PdfToolsException(HttpStatus.SERVICE_UNAVAILABLE, PdfToolsException.BUSY,
                        "The PDF tools are busy; retry in a moment");
            }
            try {
                Path out = dir.resolve("out-" + UUID.randomUUID() + PDF_EXTENSION);
                engine.compose(refs, outline, title, out);
                return out;
            } finally {
                slots.release();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ── write-back ──────────────────────────────────────────────────────────

    private Mono<PdfOutputInfo> write(String operation, Path out, int pageCount, OutputTarget output,
                                      OutputMode defaultMode, Source target, String name, List<UUID> sourceIds) {
        Mono<PdfOutputInfo> written = output.modeOr(defaultMode) == OutputMode.NEW_VERSION
                ? writeNewVersion(out, target, output.signatureLossAcknowledged(), pageCount)
                : writeNewDocument(out, name,
                output.folderId() != null ? output.folderId() : target.document().getParentId(),
                output.allowDuplicates(), pageCount);
        return written.flatMap(info -> audit(operation, info, sourceIds));
    }

    private Mono<PdfOutputInfo> writeNewDocument(Path file, String name, UUID folderId, boolean allowDuplicates, int pageCount) {
        return fileSize(file).flatMap(size -> documentService
                .uploadDocument(new PathFilePart("file", name, file), size, folderId, null, allowDuplicates)
                .flatMap(response -> {
                    if (response.id() == null) {
                        return Mono.error(new PdfToolsException(PdfToolsException.PDF_INVALID,
                                "Could not save '" + name + "'" + (response.errorMessage() != null ? ": " + response.errorMessage() : "")));
                    }
                    return Mono.just(new PdfOutputInfo(response.id(), response.name(), pageCount, size, null));
                }));
    }

    private Mono<PdfOutputInfo> writeNewVersion(Path file, Source target, boolean signatureLossAcknowledged, int pageCount) {
        Document document = target.document();
        if (wormMode) {
            return Mono.error(PdfToolsException.conflict(PdfToolsException.WORM_MODE,
                    "This deployment runs in WORM mode: save the result as a new document instead"));
        }
        if (target.inspection().signed() && !signatureLossAcknowledged) {
            return Mono.error(PdfToolsException.conflict(PdfToolsException.PDF_SIGNED,
                    "'" + document.getName() + "' is digitally signed; changing its pages invalidates the signature. "
                            + "Save as a new document, or set acknowledgeSignatureLoss=true"));
        }
        return hasActiveEnvelope(document.getId())
                .flatMap(active -> active
                        ? Mono.error(PdfToolsException.conflict(PdfToolsException.ACTIVE_SIGNATURE_ENVELOPE,
                        "'" + document.getName() + "' is being signed (active e-Sign envelope); save the result as a new document"))
                        : fileSize(file))
                .flatMap(size -> documentService
                        .replaceDocumentContent(document.getId(), new PathFilePart("file", document.getName(), file),
                                new ContentInfo(size, null))
                        .flatMap(updated -> storageService.getLatestVersionId(updated.getStoragePath())
                                .map(Optional::of).defaultIfEmpty(Optional.empty())
                                .map(version -> new PdfOutputInfo(updated.getId(), updated.getName(), pageCount, size,
                                        version.orElse(null)))));
    }

    private Mono<PdfOutputInfo> audit(String operation, PdfOutputInfo info, List<UUID> sourceIds) {
        return auditService.logAction(AuditAction.PDF_TRANSFORM, DocumentType.FILE, info.documentId(),
                        new PdfTransformAudit(operation, sourceIds, info.pageCount(), info.name()))
                .thenReturn(info);
    }

    private static Mono<Long> fileSize(Path file) {
        return Mono.fromCallable(() -> Files.size(file)).subscribeOn(Schedulers.boundedElastic());
    }

    // ── names ───────────────────────────────────────────────────────────────

    /** The requested name, or the fallback, sanitised and guaranteed to end with ".pdf". */
    static String outputName(String requested, String fallback) {
        String base = requested != null && !requested.isBlank() ? requested : fallback;
        String name = sanitizeName(base);
        if (!name.toLowerCase(Locale.ROOT).endsWith(PDF_EXTENSION)) {
            name = name + PDF_EXTENSION;
        }
        return name;
    }

    static String sanitizeName(String name) {
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty() || cleaned.equals(".") || cleaned.equals("..")) {
            cleaned = "document";
        }
        if (cleaned.length() > 200) {
            cleaned = cleaned.substring(0, 200).trim();
        }
        return cleaned;
    }

    static String stripExtension(String name) {
        if (name == null) {
            return "document";
        }
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    // ── workspace ───────────────────────────────────────────────────────────

    private <T> Mono<T> withWorkspace(Function<Path, Mono<T>> body) {
        return Mono.using(() -> Files.createTempDirectory("openfilz-pdf-"), body, PdfToolsServiceImpl::deleteQuietly);
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.debug("Could not delete temp file {}: {}", p, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.debug("Could not clean workspace {}: {}", dir, e.getMessage());
        }
    }

    /** Distinct ids in first-seen order (helper for callers building batches). */
    static List<UUID> distinct(List<UUID> ids) {
        return new ArrayList<>(new LinkedHashSet<>(ids));
    }
}
