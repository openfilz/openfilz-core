package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse.*;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.openfilz.dms.service.OnlyOfficeService;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.enums.AuditAction.REPLACE_DOCUMENT_CONTENT;
import static org.openfilz.dms.enums.DocumentType.FILE;

/**
 * Implementation of OnlyOfficeService for document editing with OnlyOffice DocumentServer.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true")
public class OnlyOfficeServiceImpl implements OnlyOfficeService {

    private final OnlyOfficeProperties properties;
    private final OnlyOfficeJwtService jwtService;
    private final DocumentDAO documentDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final WebClient.Builder webClientBuilder;

    @Override
    public Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, String userId, String userName, boolean canEdit) {
        return documentDAO.findById(documentId, canEdit ? AccessType.RW : AccessType.RO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .map(document -> buildEditorConfig(document, userId, userName, canEdit));
    }

    @Override
    public Mono<Void> handleCallback(UUID documentId, OnlyOfficeCallbackRequest callback) {
        log.info("OnlyOffice callback for document {}: status={}, key={}", documentId, callback.status(), callback.key());

        if (callback.shouldSave()) {
            return saveDocumentFromCallback(documentId, callback);
        } else if (callback.isError()) {
            log.error("OnlyOffice error for document {}: status={}", documentId, callback.status());
        } else {
            log.debug("OnlyOffice status for document {}: {} (no action needed)", documentId, callback.status());
        }

        return Mono.empty();
    }

    @Override
    public boolean isSupported(String fileName) {
        if (fileName == null) {
            return false;
        }
        String extension = getFileExtension(fileName);
        return properties.isExtensionSupported(extension);
    }

    @Override
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    private OnlyOfficeConfigResponse buildEditorConfig(Document document, String userId, String userName, boolean canEdit) {
        String documentServerUrl = properties.getDocumentServer().getUrl();
        String apiJsUrl = properties.getDocumentServer().getApiUrl();

        // Generate document key (unique per version)
        String documentKey = generateDocumentKey(document);

        // Generate access token for document download
        String accessToken = jwtService.generateAccessToken(document.getId());

        // Build document URL that OnlyOffice can fetch
        String documentUrl = buildDocumentUrl(document.getId(), accessToken);

        // Build callback URL for save events
        String callbackUrl = buildCallbackUrl(document.getId());

        // Determine document type for OnlyOffice
        String documentType = getDocumentType(document.getName());
        String fileType = getFileExtension(document.getName());

        // Build configuration
        DocumentInfo docInfo = new DocumentInfo(
                fileType,
                documentKey,
                document.getName(),
                documentUrl,
                new Permissions(true, canEdit, true, canEdit, canEdit)
        );

        UserInfo userInfo = new UserInfo(
                userId != null ? userId : "anonymous",
                userName != null ? userName : "Anonymous User"
        );

        Customization customization = new Customization(
                true,   // autosave
                true,   // chat
                true,   // comments
                true    // forcesave
        );

        EditorConfig editorConfig = new EditorConfig(
                callbackUrl,
                "en",
                canEdit ? "edit" : "view",
                userInfo,
                customization
        );

        OnlyOfficeDocumentConfig config = new OnlyOfficeDocumentConfig(
                docInfo,
                editorConfig,
                documentType
        );

        // Generate JWT token for the entire config
        String token = generateConfigToken(config);

        return new OnlyOfficeConfigResponse(documentServerUrl, apiJsUrl, config, token);
    }

    private String generateDocumentKey(Document document) {
        // Key format: documentId_timestamp
        // This ensures a new key after each save, invalidating OnlyOffice cache
        long timestamp = document.getUpdatedAt() != null
                ? document.getUpdatedAt().toEpochSecond()
                : document.getCreatedAt().toEpochSecond();
        return document.getId().toString() + "_" + timestamp;
    }

    private String buildDocumentUrl(UUID documentId, String accessToken) {
        // URL for OnlyOffice to download the document
        // Use apiBaseUrl which can be configured to host.docker.internal for Docker setups
        String baseUrl = properties.getApiBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + "api/v1/documents/" + documentId + "/onlyoffice-download?token=" + accessToken;
    }

    private String buildCallbackUrl(UUID documentId) {
        // URL for OnlyOffice to send save callbacks
        // Use apiBaseUrl which can be configured to host.docker.internal for Docker setups
        String baseUrl = properties.getApiBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + "api/v1/onlyoffice/callback/" + documentId;
    }

    private String getDocumentType(String fileName) {
        String ext = getFileExtension(fileName).toLowerCase();
        return switch (ext) {
            case "doc", "docx", "odt", "rtf", "txt" -> "word";
            case "xls", "xlsx", "ods", "csv" -> "cell";
            case "ppt", "pptx", "odp" -> "slide";
            case "pdf" -> "word"; // OnlyOffice opens PDF in word mode
            default -> "word";
        };
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int lastDot = fileName.lastIndexOf('.');
        return lastDot > 0 ? fileName.substring(lastDot + 1).toLowerCase() : "";
    }

    private String generateConfigToken(OnlyOfficeDocumentConfig config) {
        Map<String, Object> payload = new HashMap<>();

        // Flatten the config into a map for JWT signing
        Map<String, Object> documentMap = new HashMap<>();
        documentMap.put("fileType", config.document().fileType());
        documentMap.put("key", config.document().key());
        documentMap.put("title", config.document().title());
        documentMap.put("url", config.document().url());

        Map<String, Object> permissionsMap = new HashMap<>();
        permissionsMap.put("download", config.document().permissions().download());
        permissionsMap.put("edit", config.document().permissions().edit());
        permissionsMap.put("print", config.document().permissions().print());
        permissionsMap.put("review", config.document().permissions().review());
        permissionsMap.put("comment", config.document().permissions().comment());
        documentMap.put("permissions", permissionsMap);

        Map<String, Object> editorConfigMap = new HashMap<>();
        editorConfigMap.put("callbackUrl", config.editorConfig().callbackUrl());
        editorConfigMap.put("lang", config.editorConfig().lang());
        editorConfigMap.put("mode", config.editorConfig().mode());

        Map<String, Object> userMap = new HashMap<>();
        userMap.put("id", config.editorConfig().user().id());
        userMap.put("name", config.editorConfig().user().name());
        editorConfigMap.put("user", userMap);

        Map<String, Object> customizationMap = new HashMap<>();
        customizationMap.put("autosave", config.editorConfig().customization().autosave());
        customizationMap.put("chat", config.editorConfig().customization().chat());
        customizationMap.put("comments", config.editorConfig().customization().comments());
        customizationMap.put("forcesave", config.editorConfig().customization().forcesave());
        editorConfigMap.put("customization", customizationMap);

        payload.put("document", documentMap);
        payload.put("editorConfig", editorConfigMap);
        payload.put("documentType", config.documentType());

        return jwtService.generateToken(payload);
    }

    private Mono<Void> saveDocumentFromCallback(UUID documentId, OnlyOfficeCallbackRequest callback) {
        if (callback.url() == null || callback.url().isEmpty()) {
            log.error("OnlyOffice callback has no download URL for document {}", documentId);
            return Mono.empty();
        }

        log.info("Downloading modified document from OnlyOffice: {}", callback.url());

        return documentDAO.findById(documentId, AccessType.RW)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .flatMap(document -> downloadAndSaveDocument(document, callback.url()));
    }

    private Mono<Void> downloadAndSaveDocument(Document document, String downloadUrl) {
        WebClient webClient = webClientBuilder.build();

        return webClient.get()
                .uri(downloadUrl)
                .retrieve()
                .bodyToFlux(DataBuffer.class)
                .collectList()
                .flatMap(dataBuffers -> {
                    // Write to a temporary file first
                    return Mono.fromCallable(() -> {
                        Path tempFile = Files.createTempFile("onlyoffice_", "_" + document.getName());
                        try (var outputStream = Files.newOutputStream(tempFile)) {
                            for (DataBuffer buffer : dataBuffers) {
                                byte[] bytes = new byte[buffer.readableByteCount()];
                                buffer.read(bytes);
                                outputStream.write(bytes);
                                DataBufferUtils.release(buffer);
                            }
                        }
                        return tempFile;
                    }).subscribeOn(Schedulers.boundedElastic());
                })
                .flatMap(tempFile -> {
                    // Update the document in storage
                    return updateDocumentInStorage(document, tempFile);
                })
                .then(auditService.logAction(REPLACE_DOCUMENT_CONTENT, FILE, document.getId()))
                .doOnSuccess(v -> log.info("Document {} saved from OnlyOffice", document.getId()))
                .doOnError(e -> log.error("Failed to save document {} from OnlyOffice: {}", document.getId(), e.getMessage()));
    }

    private Mono<Void> updateDocumentInStorage(Document document, Path tempFile) {
        return Mono.fromCallable(() -> {
                    long size = Files.size(tempFile);
                    return size;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(newSize -> {
                    // Delete old file and save new one
                    return storageService.deleteFile(document.getStoragePath())
                            .then(Mono.defer(() -> {
                                // Read temp file and save to storage
                                try {
                                    PipedInputStream pis = new PipedInputStream();
                                    PipedOutputStream pos = new PipedOutputStream(pis);

                                    // Write temp file content to piped stream in background
                                    Mono.fromRunnable(() -> {
                                        try {
                                            Files.copy(tempFile, pos);
                                            pos.close();
                                        } catch (IOException e) {
                                            throw new RuntimeException("Failed to copy temp file", e);
                                        } finally {
                                            try {
                                                Files.deleteIfExists(tempFile);
                                            } catch (IOException ignored) {}
                                        }
                                    }).subscribeOn(Schedulers.boundedElastic()).subscribe();

                                    // This is a simplified approach - in production, you'd want to use FilePart
                                    // For now, we'll use the existing storage path approach
                                    return copyTempFileToStorage(document, tempFile, newSize);
                                } catch (IOException e) {
                                    return Mono.error(e);
                                }
                            }));
                });
    }

    private Mono<Void> copyTempFileToStorage(Document document, Path tempFile, long newSize) {
        // For file system storage, we can directly copy
        // For MinIO, we'd need to upload the file
        return Mono.fromRunnable(() -> {
                    try {
                        // Get the base storage path from config
                        String storagePath = document.getStoragePath();
                        Path targetPath = Path.of(storagePath);

                        // Ensure parent directories exist
                        if (targetPath.getParent() != null) {
                            Files.createDirectories(targetPath.getParent());
                        }

                        // Copy the temp file to storage location
                        Files.copy(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING);

                        // Delete temp file
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        throw new RuntimeException("Failed to save document to storage", e);
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.defer(() -> {
                    // Update document size and timestamp
                    document.setSize(newSize);
                    document.setUpdatedAt(OffsetDateTime.now());
                    return documentDAO.update(document);
                }))
                .then();
    }
}
