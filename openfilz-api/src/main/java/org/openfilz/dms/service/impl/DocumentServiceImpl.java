package org.openfilz.dms.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.openfilz.dms.dto.audit.*;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.*;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.PhysicalDocument;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.DuplicateNameException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.exception.StorageException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static org.openfilz.dms.enums.AuditAction.*;
import static org.openfilz.dms.enums.DocumentType.FILE;
import static org.openfilz.dms.enums.DocumentType.FOLDER;
import static org.openfilz.dms.utils.FileConstants.SLASH;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentServiceImpl implements DocumentService {

    public static final String APPLICATION_OCTET_STREAM = "application/octet-stream";

    protected final StorageService storageService;
    protected final ObjectMapper objectMapper; // For JSONB processing
    protected final AuditService auditService; // For auditing
    protected final JsonUtils jsonUtils;
    protected final DocumentDAO documentDAO;
    protected final UserInfoService userInfoService;

    @Value("${piped.buffer.size:1024}")
    private Integer pipedBufferSize;


    @Override
    @Transactional // Ensure R2DBC @Transactional is properly configured if complex operations span DB and FS
    public Mono<FolderResponse> createFolder(CreateFolderRequest request, Authentication auth) {
        if (request.name().contains(StorageService.FOLDER_SEPARATOR)) {
            return Mono.error(new OperationForbiddenException("Folder name should not contains any '/'"));
        }
        return doCreateFolder(request, auth, null, false, null)
                .flatMap(savedFolder -> Mono.just(new FolderResponse(savedFolder.getId(), savedFolder.getName(), savedFolder.getParentId())));
    }

    private Mono<Document> doCreateFolder(CreateFolderRequest request, Authentication auth, Json folderMetadata,
                                          boolean copy, UUID sourceFolderId) {
        log.debug("doCreateFolder folder {}", request);
        return documentExists(auth, request.name(), request.parentId())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateNameException(FOLDER, request.name()));
                    }
                    if(request.parentId() != null) {
                        return documentDAO.existsByIdAndType(auth, request.parentId(), FOLDER, AccessType.RW).flatMap(folderExists->{
                            if(!folderExists) {
                                return Mono.error(new DocumentNotFoundException(FOLDER, request.parentId()));
                            }
                            return saveFolderInRepository(request, auth, folderMetadata);
                        });
                    }
                    return saveFolderInRepository(request, auth, folderMetadata);
                }).flatMap(savedFolder -> {
                    if (sourceFolderId == null) {
                        return auditService.logAction(auth, copy ? AuditAction.COPY_FOLDER : AuditAction.CREATE_FOLDER, FOLDER, savedFolder.getId(), new CreateFolderAudit(request))
                                .thenReturn(savedFolder);
                    }
                    return auditService.logAction(auth, copy ? AuditAction.COPY_FOLDER : AuditAction.CREATE_FOLDER, FOLDER, savedFolder.getId(), new CreateFolderAudit(request, sourceFolderId))
                            .thenReturn(savedFolder);
                });

    }

    private Mono<Document> doSaveDocument(Authentication auth, Function<String, Mono<Document>> documentFunction) {
        return userInfoService.getConnectedUser(auth).flatMap(documentFunction);
    }

    private Mono<Document> saveFolderInRepository(CreateFolderRequest request, Authentication auth, Json folderMetadata) {
        Document.DocumentBuilder documentBuilder = Document.builder()
                .name(request.name())
                .type(FOLDER)
                .parentId(request.parentId())
                .metadata(folderMetadata);
        return doSaveDocument(auth,  saveNewDocumentFunction(documentBuilder));
    }

    private Function<String, Mono<Document>> saveNewDocumentFunction(Document.DocumentBuilder request) {
        return username -> {
            Document document = request
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .createdBy(username)
                    .updatedBy(username)
                    .build();
            return documentDAO.create(document);
        };
    }

    private Mono<Document> saveDocumentInDB(FilePart filePart, String storagePath, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Authentication auth) {
        Document.DocumentBuilder documentBuilder = Document.builder()
                .name(originalFilename)
                .type(FILE)
                .contentType(filePart.headers().getContentType() != null ? filePart.headers().getContentType().toString() : APPLICATION_OCTET_STREAM)
                .size(contentLength)
                .parentId(parentFolderId)
                .storagePath(storagePath)
                .metadata(jsonUtils.toJson(metadata));
        return doSaveDocument(auth,  saveNewDocumentFunction(documentBuilder));
    }

    private Mono<Boolean> documentExists(Authentication auth, String documentName, UUID parentFolderId) {
        return documentDAO.existsByNameAndParentId(auth, documentName, parentFolderId);
    }


    @Override
    @Transactional
    public Mono<UploadResponse> uploadDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, Boolean allowDuplicateFileNames, Authentication auth) {
        String originalFilename = filePart.filename().replace(StorageService.FILENAME_SEPARATOR, "");
        if (parentFolderId != null) {
            return documentDAO.existsByIdAndType(auth, parentFolderId, FOLDER, AccessType.RW)
                    .flatMap(exists -> {
                        if (!exists) {
                            return Mono.error(new DocumentNotFoundException(FOLDER, parentFolderId));
                        }
                        return doUploadDocument(filePart, contentLength, parentFolderId, metadata, originalFilename, allowDuplicateFileNames, auth);
                    });
        }
        return doUploadDocument(filePart, contentLength, null, metadata, originalFilename, allowDuplicateFileNames, auth);
    }

    private Mono<UploadResponse> doUploadDocument(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Boolean allowDuplicateFileNames, Authentication auth) {
        if(allowDuplicateFileNames) {
            return storageService.saveFile(filePart)
                    .flatMap(storagePath -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, metadata, originalFilename, auth, storagePath))
                    .flatMap(savedDoc -> auditUploadActionAndReturnResponse(parentFolderId, metadata, auth, savedDoc));
        }
        Mono<Boolean> duplicateCheck = documentExists(auth, originalFilename, parentFolderId);
        return duplicateCheck.flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateNameException(FILE, originalFilename));
                    }
                    return storageService.saveFile(filePart);
                })
                .flatMap(storagePath -> saveDocumentInDatabase(filePart, contentLength, parentFolderId, metadata, originalFilename, auth, storagePath))
                .flatMap(savedDoc -> auditUploadActionAndReturnResponse(parentFolderId, metadata, auth, savedDoc));
    }

    private Mono<UploadResponse> auditUploadActionAndReturnResponse(UUID parentFolderId, Map<String, Object> metadata, Authentication auth, Document savedDoc) {
        return auditService.logAction(auth, AuditAction.UPLOAD_DOCUMENT, FILE, savedDoc.getId(), new UploadAudit(savedDoc.getName(), parentFolderId, metadata))
                .thenReturn(new UploadResponse(savedDoc.getId(), savedDoc.getName(), savedDoc.getContentType(), savedDoc.getSize()));
    }


    private Mono<Document> saveDocumentInDatabase(FilePart filePart, Long contentLength, UUID parentFolderId, Map<String, Object> metadata, String originalFilename, Authentication auth, String storagePath) {
        if(contentLength == null) {
            return storageService.getFileLength(storagePath)
                    .flatMap(fileLength -> saveDocumentInDB(filePart, storagePath, fileLength, parentFolderId, metadata, originalFilename, auth));
        }
        return saveDocumentInDB(filePart, storagePath, contentLength, parentFolderId, metadata, originalFilename, auth);
    }



    @Override
    @Transactional
    public Mono<? extends Resource> downloadDocument(Document doc, Authentication auth) {
        return doc.getType() == FILE ?
                storageService.loadFile(doc.getStoragePath())
                : zipFolder(documentDAO.getChildren(doc.getId()))
                .flatMap(r -> auditService.logAction(auth, AuditAction.DOWNLOAD_DOCUMENT, FILE, doc.getId())
                        .thenReturn(r));
    }

    private Mono<? extends Resource> zipFolder(Flux<ChildElementInfo> children) {
        try {
            PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
            PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);
            ZipArchiveOutputStream zos = new ZipArchiveOutputStream(pipedOutputStream);
            children.concatMap(element -> addDocumentToZip(element, zos))
                    .then(Mono.just(true))
                    .doOnTerminate(() -> closeOutputStream(zos))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error -> log.error("Error during zip creation", error)
                    );

            return Mono.just(new InputStreamResource(pipedInputStream));
        } catch (IOException e) {
            return Mono.error(new StorageException("Failed to create piped stream", e));
        }
    }

    private Mono<Boolean> addDocumentToZip(ChildElementInfo element, ZipArchiveOutputStream zos) {
        if(element.getType().equals(FILE)) {
            return addFileToZip(element, element.getPath(), zos);
        }
        return addFolderToZip(element, zos);
    }

    @Override
    @Transactional
    public Mono<Void> deleteFiles(DeleteRequest request, Authentication auth) {
        return Flux.fromIterable(request.documentIds())
                .flatMap(docId -> documentDAO.findById(docId, auth, AccessType.RWD)
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(docId)))
                        .filter(doc -> doc.getType() == FILE) // Ensure it's a file
                        .switchIfEmpty(Mono.error(new OperationForbiddenException("ID " + docId + " is a folder. Use delete folders API.")))
                        .flatMap(document -> storageService.deleteFile(document.getStoragePath())
                                .then(documentDAO.delete(document)))
                        .then(auditService.logAction(auth, AuditAction.DELETE_FILE, FILE, docId))
                )
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> deleteFolders(DeleteRequest request, Authentication auth) {
        return Flux.fromIterable(request.documentIds())
                .flatMap(folderId -> deleteFolderRecursive(folderId, auth))
                .then();
    }

    private Mono<Void> deleteFolderRecursive(UUID folderId, Authentication auth) {
        return documentDAO.getFolderToDelete(auth, folderId)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, folderId)))
                .flatMap(folder -> {
                    // 1. Delete child files
                    Mono<Void> deleteChildFiles = getChildrenDocumentsToDelete(folderId, FILE, auth)
                            .flatMap(file -> storageService.deleteFile(file.getStoragePath())
                                    .then(documentDAO.delete(file))
                                    .then(auditService.logAction(auth, DELETE_FILE_CHILD, FILE, file.getId(), new DeleteAudit(folderId)))
                            ).then();

                    // 2. Recursively delete child folders
                    Mono<Void> deleteChildFolders = getChildrenDocumentsToDelete(folderId, FOLDER, auth)
                            .flatMap(childFolder -> deleteFolderRecursive(childFolder.getId(), auth))
                            .then();

                    // 3. Delete the folder itself from DB (and storage if it had a physical representation)
                    return Mono.when(deleteChildFiles, deleteChildFolders)
                            .then(documentDAO.delete(folder))
                            .then(auditService.logAction(auth, AuditAction.DELETE_FOLDER, FOLDER, folderId));
                });
    }

    protected Flux<Document> getChildrenDocumentsToDelete(UUID folderId, DocumentType docType, Authentication auth) {
        return documentDAO.findDocumentsByParentIdAndType(auth, folderId, docType);
    }


    @Override
    @Transactional
    public Mono<Void> moveFiles(MoveRequest request, Authentication auth) {
        if(request.targetFolderId() == null) {
            return doMoveFiles(request, auth);
        }
        // 1. Validate target folder exists and is a folder
        Mono<Document> targetFolderMono = documentDAO.findById(request.targetFolderId(), auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, request.targetFolderId())))
                .filter(doc -> doc.getType() == DocumentType.FOLDER)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Target is not a folder: " + request.targetFolderId())));

        return targetFolderMono.flatMap(_ ->
                doMoveFiles(request, auth)
        );
    }

    private Mono<Void> doMoveFiles(MoveRequest request, Authentication auth) {
        return Flux.fromIterable(request.documentIds())
                .flatMap(fileId -> documentDAO.findById(fileId, auth, AccessType.RWD)
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(FILE, fileId)))
                        .filter(doc -> doc.getType() == FILE) // Ensure it's a file being moved
                        .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot move folder using file move API: " + fileId)))
                        .flatMap(fileToMove -> {
                            // Check for name collision in target folder
                            return moveDocument(request, auth, fileToMove)
                                    .flatMap(movedFile -> auditService.logAction(auth, MOVE_FILE, FILE, movedFile.getId(),
                                            new MoveAudit(request.targetFolderId())));
                        })
                )
                .then();
    }

    @Override
    @Transactional
    public Mono<Void> moveFolders(MoveRequest request, Authentication auth) {
        Mono<Document> targetFolderMono = documentDAO.findById(request.targetFolderId(), auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, request.targetFolderId())))
                .filter(doc -> doc.getType() == DocumentType.FOLDER)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Target is not a folder: " + request.targetFolderId())));

        return targetFolderMono.flatMap(targetFolder ->
                Flux.fromIterable(request.documentIds())
                        .flatMap(folderIdToMove -> {
                            if (folderIdToMove.equals(request.targetFolderId())) {
                                return Mono.error(new OperationForbiddenException("Cannot move a folder into itself."));
                            }
                            // Check for moving a parent into its child (cycle) - more complex check needed for full hierarchy
                            Mono<Boolean> isMovingToDescendant = isDescendant(request.targetFolderId(), folderIdToMove, auth);

                            return isMovingToDescendant.flatMap(isDescendant -> {
                                if (isDescendant) {
                                    return Mono.error(new OperationForbiddenException("Cannot move a folder into one of its descendants."));
                                }

                                return documentDAO.findById(folderIdToMove, auth, AccessType.RWD)
                                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, folderIdToMove)))
                                        .filter(doc -> doc.getType() == DocumentType.FOLDER)
                                        .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot move file using folder move API: " + folderIdToMove)))
                                        .flatMap(folderToMove -> {
                                            // Check for name collision in target folder
                                            return moveDocument(request, auth, folderToMove)
                                                    .flatMap(movedFolder -> auditService.logAction(auth, MOVE_FOLDER, FOLDER, movedFolder.getId(),
                                                            new MoveAudit(request.targetFolderId())));
                                        });
                            });
                        })
                        .then()
        );
    }

    private Mono<Document> moveDocument(MoveRequest request, Authentication auth, Document documentToMove) {
        if(documentToMove.getParentId() == request.targetFolderId()) {
            return Mono.error(new DuplicateNameException("Impossible to move a document in the same folder : you may want to use /copy instead"));
        }
        if(request.allowDuplicateFileNames() != null && request.allowDuplicateFileNames()) {
            return doMoveDocuments(request, auth, documentToMove);
        }
        return documentDAO.existsByNameAndParentId(auth, documentToMove.getName(), request.targetFolderId())
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new DuplicateNameException(
                                "A file/folder with name '" + documentToMove.getName() + "' already exists in the target folder."));
                    }
                    return doMoveDocuments(request, auth, documentToMove);
                });
    }

    private Mono<Document> doMoveDocuments(MoveRequest request, Authentication auth, Document documentToMove) {
        return doSaveDocument(auth, username -> {
            documentToMove.setParentId(request.targetFolderId());
            documentToMove.setUpdatedAt(OffsetDateTime.now());
            documentToMove.setUpdatedBy(username);
            return documentDAO.update(documentToMove);
        });
    }

    // Helper to check if 'potentialChildId' is a descendant of 'potentialParentId'
    private Mono<Boolean> isDescendant(UUID potentialChildId, UUID potentialParentId, Authentication auth) {
        if (potentialChildId == null || potentialParentId == null) {
            return Mono.just(false);
        }
        if (potentialChildId.equals(potentialParentId)) { // A folder cannot be its own descendant in this context.
            return Mono.just(true); // Or false depending on definition, for move, this is an invalid state
        }

        return documentDAO.findById(potentialChildId, auth, null)
                .flatMap(childDoc -> {
                    if (childDoc.getParentId() == null) {
                        return Mono.just(false); // Reached root without finding potentialParentId
                    }
                    if (childDoc.getParentId().equals(potentialParentId)) {
                        return Mono.just(true); // Found potentialParentId as direct parent
                    }
                    return isDescendant(childDoc.getParentId(), potentialParentId, auth); // Recurse up
                })
                .defaultIfEmpty(false); // Child not found, so not a descendant
    }


    @Override
    @Transactional
    public Flux<CopyResponse> copyFiles(CopyRequest request, Authentication auth) {
        if (request.targetFolderId() == null) {
            return doCopyFiles(request, auth);
        }
        Mono<Document> targetFolderMono = documentDAO.findById(request.targetFolderId(), auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, request.targetFolderId())))
                .filter(doc -> doc.getType() == DocumentType.FOLDER)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Target is not a folder: " + request.targetFolderId())));

        return targetFolderMono.flatMapMany(_ ->
                doCopyFiles(request, auth)
        );
    }

    private Flux<CopyResponse> doCopyFiles(CopyRequest request, Authentication auth) {
        return Flux.fromIterable(request.documentIds())
                .flatMapSequential(fileIdToCopy -> documentDAO.findById(fileIdToCopy, auth, AccessType.RO)
                        .switchIfEmpty(Mono.error(new DocumentNotFoundException(FILE, fileIdToCopy)))
                        .filter(doc -> doc.getType() == FILE)
                        .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot copy folder using file copy API: " + fileIdToCopy)))
                        .flatMap(originalFile -> raiseErrorIfExists(auth, originalFile.getName(), request.targetFolderId(), request.allowDuplicateFileNames())
                                .flatMap(filename -> storageService.copyFile(originalFile.getStoragePath())
                                        .flatMap(newStoragePath -> {
                                            // 2. Create new DB entry for the copied file
                                            Document.DocumentBuilder copiedFile = Document.builder()
                                                    .name(originalFile.getName()) // Handle potential name collision, e.g., "file (copy).txt"
                                                    .type(FILE)
                                                    .contentType(originalFile.getContentType())
                                                    .size(originalFile.getSize())
                                                    .parentId(request.targetFolderId())
                                                    .storagePath(newStoragePath)
                                                    .metadata(jsonUtils.cloneOrNewEmptyJson(originalFile.getMetadata()));
                                            return doSaveDocument(auth, saveNewDocumentFunction(copiedFile));
                                        })
                                        .flatMap(cf -> auditService.logAction(auth, COPY_FILE, FILE, cf.getId(),
                                                        new CopyAudit(fileIdToCopy, request.targetFolderId()))
                                                .thenReturn(new CopyResponse(fileIdToCopy, cf.getId()))))
                        )
                );
    }


    // check if alrerady exists in DB
    private Mono<String> raiseErrorIfExists(Authentication auth, String originalName, UUID parentId, Boolean allowDuplicateFileNames) {
        if(allowDuplicateFileNames != null && allowDuplicateFileNames) {
            return Mono.just(originalName);
        }
        Mono<Boolean> existsCheck = documentExists(auth, originalName, parentId);

        return existsCheck.flatMap(exists -> {
            if (exists) {
                return Mono.error(new DuplicateNameException(FOLDER, originalName));
            }
            return Mono.just(originalName);
        });
    }


    @Override
    @Transactional
    public Flux<UUID> copyFolders(CopyRequest request, Authentication auth) {
        Mono<Document> targetFolderMono = documentDAO.findById(request.targetFolderId(), auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, request.targetFolderId())))
                .filter(doc -> doc.getType() == DocumentType.FOLDER)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Target is not a folder: " + request.targetFolderId())));

        return targetFolderMono.flatMapMany(_ ->
                Flux.fromIterable(request.documentIds())
                        .concatMap(folderIdToCopy -> {
                            if (folderIdToCopy.equals(request.targetFolderId())) {
                                return Flux.error(new OperationForbiddenException("Cannot copy a folder into itself."));
                            }
                            // Add check for copying a parent into its child if needed, similar to move
                            return copyFolderRecursive(folderIdToCopy, request.targetFolderId(), request.allowDuplicateFileNames(), auth);
                        })
        );
    }

   private Flux<UUID> copyFolderRecursive(UUID sourceFolderId, UUID targetParentFolderId, Boolean allowDuplicateFileNames, Authentication auth) {
        return documentDAO.findById(sourceFolderId, auth, AccessType.RO)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, sourceFolderId)))
                .flatMapMany(sourceFolder -> {
                    // Generate unique name for the new folder in the target location
                    return raiseErrorIfExists(auth, sourceFolder.getName(), targetParentFolderId, allowDuplicateFileNames)
                            .flatMap(_ -> doCreateFolder(new CreateFolderRequest(sourceFolder.getName(), targetParentFolderId), auth,
                                    jsonUtils.cloneOrNewEmptyJson(sourceFolder.getMetadata()), true, sourceFolderId))
                            .flatMap(savedNewFolder -> {
                                UUID newFolderId = savedNewFolder.getId();

                                // 2. Copy child files
                                Flux<Document> copyChildFiles = documentDAO.findDocumentsByParentIdAndType(auth, sourceFolderId, FILE)
                                        .flatMap(childFile -> {
                                            // For each child file, copy physical file and create DB entry under newFolderId
                                            return raiseErrorIfExists(auth, childFile.getName(), newFolderId, allowDuplicateFileNames)
                                                    .flatMap(_ -> storageService.copyFile(childFile.getStoragePath())
                                                            .flatMap(newChildFileName -> {
                                                                Document.DocumentBuilder documentBuilder = Document.builder()
                                                                        .name(childFile.getName())
                                                                        .type(FILE)
                                                                        .contentType(childFile.getContentType())
                                                                        .size(childFile.getSize())
                                                                        .parentId(newFolderId)
                                                                        .storagePath(newChildFileName)
                                                                        .metadata(jsonUtils.cloneOrNewEmptyJson(childFile.getMetadata()));
                                                                return doSaveDocument(auth, saveNewDocumentFunction(documentBuilder));
                                                            })
                                                            .flatMap(ccf -> auditService.logAction(auth, COPY_FILE_CHILD, FILE, ccf.getId(),
                                                                    new CopyAudit(childFile.getId(), newFolderId, sourceFolderId)).thenReturn(ccf)));
                                        });

                                // 3. Recursively copy child folders
                                Flux<UUID> copyChildSubFolders = documentDAO.findDocumentsByParentIdAndType(auth, sourceFolderId, FOLDER)
                                        .flatMap(childSubFolder -> copyFolderRecursive(childSubFolder.getId(), newFolderId, allowDuplicateFileNames, auth));

                                return Mono.when(copyChildFiles, copyChildSubFolders).thenReturn(newFolderId);
                            });
                });
    }


    @Override
    @Transactional
    public Mono<Document> renameFile(UUID fileId, RenameRequest request, Authentication auth) {
        return documentDAO.findById(fileId, auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FILE, fileId)))
                .filter(doc -> doc.getType() == FILE)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot rename folder using file rename API: " + fileId)))
                .flatMap(fileToRename -> {
                    // Check for name collision in its current parent folder
                    Mono<Boolean> duplicateCheck = documentExists(auth, request.newName(), fileToRename.getParentId());
                    return saveFileToRename(request, auth, fileToRename, duplicateCheck);
                })
                .flatMap(renamedFile -> auditService.logAction(auth, RENAME_FILE, FILE, renamedFile.getId(),
                        new RenameAudit(request.newName())).thenReturn(renamedFile));
    }

    private Mono<Document> saveFileToRename(RenameRequest request, Authentication auth, Document fileToRename, Mono<Boolean> duplicateCheck) {
        return duplicateCheck.flatMap(exists -> {
            if (exists) {
                return Mono.error(new DuplicateNameException(
                        "A file/folder with name '" + request.newName() + "' already exists in the current location."));
            }
            return doSaveDocument(auth, username -> {
                fileToRename.setName(request.newName());
                fileToRename.setUpdatedAt(OffsetDateTime.now());
                fileToRename.setUpdatedBy(username);
                return documentDAO.update(fileToRename);
            });
        });
    }

    @Override
    @Transactional
    public Mono<Document> renameFolder(UUID folderId, RenameRequest request, Authentication auth) {
        return documentDAO.findById(folderId, auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(FOLDER, folderId)))
                .filter(doc -> doc.getType() == DocumentType.FOLDER)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot rename file using folder rename API: " + folderId)))
                .flatMap(folderToRename -> {
                    if (folderToRename.getName().equals(request.newName())) {
                        return Mono.error(new DuplicateNameException("The folder has already the name provided"));
                    }
                    Mono<Boolean> duplicateCheck = documentExists(auth, request.newName(), folderToRename.getParentId());

                    return saveFileToRename(request, auth, folderToRename, duplicateCheck);
                })
                .flatMap(renamedFolder -> auditService.logAction(auth, RENAME_FOLDER, FOLDER, renamedFolder.getId(),
                        new RenameAudit(request.newName())).thenReturn(renamedFolder));
    }


    @Override
    @Transactional
    public Mono<Document> replaceDocumentContent(UUID documentId, FilePart newFilePart, Long contentLength, Authentication auth) {
        return documentDAO.findById(documentId, auth, AccessType.RWD)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .filter(doc -> doc.getType() == FILE) // Only files have content to replace
                .switchIfEmpty(Mono.error(new OperationForbiddenException("Cannot replace content of a folder: " + documentId)))
                .flatMap(document -> {
                    // 1. Save new file content
                    String oldStoragePath = document.getStoragePath();

                    return storageService.saveFile(newFilePart)
                            .flatMap(newStoragePath ->
                                    replaceDocumentContentAndSave(newFilePart, contentLength, auth, document, newStoragePath, oldStoragePath));
                });
    }

    private Mono<Document> replaceDocumentContentAndSave(FilePart newFilePart, Long contentLength, Authentication auth, Document document, String newStoragePath, String oldStoragePath) {
        if(contentLength == null) {
            return storageService.getFileLength(newStoragePath)
                    .flatMap(fileLength -> replaceDocumentInDB(newFilePart, newStoragePath, oldStoragePath, fileLength, auth, document));
        }
        return replaceDocumentInDB(newFilePart, newStoragePath, oldStoragePath, contentLength, auth, document);
    }

    private Mono<Document> replaceDocumentInDB(FilePart newFilePart, String newStoragePath, String oldStoragePath, Long contentLength, Authentication auth, Document document) {
        return doSaveDocument(auth, username -> {
            document.setStoragePath(newStoragePath);
            document.setContentType(newFilePart.headers().getContentType() != null ? newFilePart.headers().getContentType().toString() : APPLICATION_OCTET_STREAM);
            document.setUpdatedAt(OffsetDateTime.now());
            document.setUpdatedBy(username);
            document.setSize(contentLength);
            return documentDAO.update(document);
        }).flatMap(savedDoc -> {
                    // 3. Delete old file content from storage
                    if (oldStoragePath != null && !oldStoragePath.equals(newStoragePath)) {
                        return storageService.deleteFile(oldStoragePath).thenReturn(savedDoc);
                    }
                    return Mono.just(savedDoc);
                })
                .flatMap(updatedDoc -> auditService.logAction(auth, REPLACE_DOCUMENT_CONTENT, FILE, updatedDoc.getId(),
                        new ReplaceAudit(newFilePart.filename())))
                .thenReturn(document);

    }

    @Override
    @Transactional
    public Mono<Document> replaceDocumentMetadata(UUID documentId, Map<String, Object> newMetadata, Authentication auth) {

        return documentDAO.findById(documentId, auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(document -> doSaveDocument(auth, username -> {
                    document.setMetadata(newMetadata != null ? Json.of(objectMapper.valueToTree(newMetadata).toString()) : null);
                    document.setUpdatedAt(OffsetDateTime.now());
                    document.setUpdatedBy(username);
                    return documentDAO.update(document);
                }))
                .flatMap(updatedDoc -> auditService.logAction(auth, REPLACE_DOCUMENT_METADATA, updatedDoc.getType(), updatedDoc.getId(),
                        new ReplaceAudit(newMetadata)).thenReturn(updatedDoc));
    }

    @Override
    @Transactional
    public Mono<Document> updateDocumentMetadata(UUID documentId, UpdateMetadataRequest request, Authentication auth) {
        return documentDAO.findById(documentId, auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(document -> doSaveDocument(auth, username -> {
                    JsonNode currentMetadata = jsonUtils.toJsonNode(document.getMetadata());
                    ObjectNode updatedMetadataNode;

                    if (currentMetadata == null || currentMetadata.isNull() || !currentMetadata.isObject()) {
                        updatedMetadataNode = objectMapper.createObjectNode();
                    } else {
                        updatedMetadataNode = currentMetadata.deepCopy();
                    }

                    for (Map.Entry<String, Object> entry : request.metadataToUpdate().entrySet()) {
                        updatedMetadataNode.set(entry.getKey(), objectMapper.valueToTree(entry.getValue()));
                    }
                    document.setMetadata(jsonUtils.toJson(updatedMetadataNode));
                    document.setUpdatedAt(OffsetDateTime.now());
                    document.setUpdatedBy(username);
                    return documentDAO.update(document);
                }))
                .flatMap(updatedDoc -> auditService.logAction(auth, UPDATE_DOCUMENT_METADATA, updatedDoc.getType(), updatedDoc.getId(),
                        new UpdateMetadataAudit(request.metadataToUpdate())).thenReturn(updatedDoc));
    }


    @Override
    @Transactional
    public Mono<Void> deleteDocumentMetadata(UUID documentId, DeleteMetadataRequest request, Authentication auth) {
        return documentDAO.findById(documentId, auth, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(document -> doSaveDocument(auth, username -> {
                    Map<String, Object> currentMetadata = jsonUtils.toMap(document.getMetadata());
                    if (currentMetadata == null || currentMetadata.isEmpty() || request.metadataKeysToDelete().isEmpty()) {
                        return Mono.empty(); // No metadata to delete or nothing to do
                    }


                    request.metadataKeysToDelete().forEach(currentMetadata::remove);

                    document.setMetadata(jsonUtils.toJson(currentMetadata));
                    document.setUpdatedAt(OffsetDateTime.now());
                    document.setUpdatedBy(username);
                    return documentDAO.update(document);
                }))
                .flatMap(updatedDoc -> auditService.logAction(auth, DELETE_DOCUMENT_METADATA, updatedDoc.getType(), updatedDoc.getId(),
                        new DeleteMetadataAudit(request.metadataKeysToDelete())));
    }


    @Override
    public Mono<Resource> downloadMultipleDocumentsAsZip(List<UUID> documentIds, Authentication auth) {
        if (documentIds == null || documentIds.isEmpty()) {
            return Mono.error(new IllegalArgumentException("Document IDs list cannot be empty."));
        }

        try {
            PipedInputStream pipedInputStream = new PipedInputStream(pipedBufferSize);
            PipedOutputStream pipedOutputStream = new PipedOutputStream(pipedInputStream);
            ZipArchiveOutputStream zos = new ZipArchiveOutputStream(pipedOutputStream);

            documentDAO.getElementsAndChildren(documentIds, auth)
                    .collectList()
                    .flatMap(docs -> {
                        if (docs.isEmpty()) {
                            return Mono.error(new DocumentNotFoundException("No valid files found for the provided IDs to zip."));
                        }
                        if (docs.size() != documentIds.size()) {
                            log.warn("Some requested documents were not files or not found. Zipping available files.");
                        }
                        return Flux.fromIterable(docs)
                                .concatMap(doc ->  addDocumentToZip(doc, zos))
                                .then(Mono.just(true));
                    })
                    .doOnTerminate(() -> closeOutputStream(zos))
                    .subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                            null,
                            error -> log.error("Error during zip creation", error)
                    );

            return Mono.just(new InputStreamResource(pipedInputStream));
        } catch (IOException e) {
            return Mono.error(new StorageException("Failed to create piped stream", e));
        }
    }

    private static void closeOutputStream(ZipArchiveOutputStream zos) {
        try {
            zos.close();
        } catch (IOException e) {
            log.error("Failed to close zip stream", e);
        }
    }

    private Mono<Boolean> addFolderToZip(ChildElementInfo folderElement, ZipArchiveOutputStream zos) {
        try {
            ZipArchiveEntry zipEntry = new ZipArchiveEntry(folderElement.getPath() + SLASH);
            zos.putArchiveEntry(zipEntry);
            zos.closeArchiveEntry();
            return Mono.just(true);
        } catch (IOException ioe) {
            return Mono.error(new StorageException(ioe.getMessage()));
        }
    }

    private Mono<Boolean> addFileToZip(PhysicalDocument doc, String path, ZipArchiveOutputStream zos) {
        return storageService.loadFile(doc.getStoragePath())
                .flatMap(resource -> {
                    if (resource == null || !resource.exists()) {
                        log.warn("Skipping missing file in zip: {}", doc.getName());
                        return Mono.just(false);
                    }
                    return Mono.fromRunnable(() -> {
                        try {
                            ZipArchiveEntry zipEntry = new ZipArchiveEntry(path == null ? doc.getName() : path);
                            zipEntry.setSize(doc.getSize() != null ? doc.getSize() : resource.contentLength());
                            zos.putArchiveEntry(zipEntry);
                            try (InputStream is = resource.getInputStream()) {
                                is.transferTo(zos);
                                zos.closeArchiveEntry();
                            }
                        } catch (IOException ioe) {
                            log.error("Exception in addFileToZip", ioe);
                            throw new StorageException(ioe.getMessage());
                        }
                    }).subscribeOn(Schedulers.boundedElastic()).thenReturn(true);
                });
    }


    @Override
    public Flux<UUID> searchDocumentIdsByMetadata(SearchByMetadataRequest request, Authentication auth) {
        return documentDAO.listDocumentIds(auth, request);
    }

    @Override
    public Mono<Map<String, Object>> getDocumentMetadata(UUID documentId, SearchMetadataRequest request, Authentication auth) {
        return documentDAO.findById(documentId, auth, AccessType.RO)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .map(document -> {
                    JsonNode metadataNode = jsonUtils.toJsonNode(document.getMetadata());
                    if (metadataNode == null || metadataNode.isNull()) {
                        return new HashMap<String, Object>();
                    }
                    Map<String, Object> allMetadata = objectMapper.convertValue(metadataNode, Map.class);

                    if (request.metadataKeys() != null && !request.metadataKeys().isEmpty()) {
                        Map<String, Object> filteredMetadata = new HashMap<>();
                        for (String key : request.metadataKeys()) {
                            if (allMetadata.containsKey(key)) {
                                filteredMetadata.put(key, allMetadata.get(key));
                            }
                        }
                        return filteredMetadata;
                    }
                    return allMetadata;
                });
    }

    // Utility method to find a document
    @Override
    public Mono<Document> findDocumentToDownloadById(UUID documentId, Authentication auth) {
        return documentDAO.findById(documentId, auth, AccessType.RO)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)));
    }

    @Override
    public Mono<DocumentInfo> getDocumentInfo(UUID documentId, Boolean withMetadata, Authentication authentication) {
        return documentDAO.findById(documentId, authentication, AccessType.RO)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(doc -> {
                    DocumentInfo info = withMetadata != null && withMetadata.booleanValue() ?
                            new DocumentInfo(doc.getType(), doc.getName(), doc.getParentId(), doc.getMetadata() != null ? jsonUtils.toMap(doc.getMetadata()) : null, doc.getSize())
                            : new DocumentInfo(doc.getType(), doc.getName(), doc.getParentId(), null, null);
                    return Mono.just(info);
                } );
    }

    @Override
    public Flux<FolderElementInfo> listFolderInfo(UUID folderId, Boolean onlyFiles, Boolean onlyFolders, Authentication authentication) {
        if(onlyFiles != null && onlyFiles && onlyFolders != null && onlyFolders) {
            return Flux.error(new IllegalArgumentException("onlyFiles and onlyFolders cannot be true in simultaneously"));
        }
        DocumentType type = (onlyFiles != null && onlyFiles) ? FILE : (onlyFolders != null && onlyFolders) ? FOLDER : null;
        if(folderId == null) {
            return listRootElements(authentication, type);
        }
        return documentDAO.existsByIdAndType(authentication, folderId, DocumentType.FOLDER, AccessType.RO)
                .flatMapMany(exists -> {
                    if(!exists) {
                        return Flux.error(new DocumentNotFoundException(FOLDER, folderId));
                    }
                    return documentDAO.listDocumentInfoInFolder(authentication, folderId, type);
                });
    }

    protected Flux<FolderElementInfo> listRootElements(Authentication authentication, DocumentType type) {
        return documentDAO.listDocumentInfoInFolder(authentication, null, type);
    }

    @Override
    public Mono<Long> countFolderElements(UUID folderId, Authentication authentication) {
        return documentDAO.countDocument(authentication, folderId);
    }

}