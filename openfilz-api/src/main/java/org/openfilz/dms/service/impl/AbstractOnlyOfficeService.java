package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.IUserInfo;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse.*;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.*;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.PathFilePart;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import static org.openfilz.dms.service.ChecksumService.SHA_256;

/**
 * Implementation of OnlyOfficeService for document editing with OnlyOffice DocumentServer.
 */
@Slf4j
@RequiredArgsConstructor
public class AbstractOnlyOfficeService<T extends IUserInfo> implements OnlyOfficeService {

    private final CommonProperties commonProperties;
    private final OnlyOfficeProperties onlyOfficeProperties;
    private final OnlyOfficeJwtService<T> jwtService;
    private final OnlyOfficeJwtExtractor<T> jwtExtactor;
    private final DocumentDAO documentDAO;
    private final DocumentService documentService;
    private final WebClient.Builder webClientBuilder;

    @Override
    public Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit) {
        return documentDAO.findById(documentId, canEdit ? AccessType.RW : AccessType.RO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .flatMap(document -> buildEditorConfig(document, canEdit));
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
        return onlyOfficeProperties.isExtensionSupported(extension);
    }

    @Override
    public boolean isEnabled() {
        return onlyOfficeProperties.isEnabled();
    }

    private Mono<OnlyOfficeConfigResponse> buildEditorConfig(Document document, boolean canEdit) {
        String documentServerUrl = onlyOfficeProperties.getDocumentServer().getUrl();
        String apiJsUrl = onlyOfficeProperties.getDocumentServer().getApiUrl();

        // Generate document key (unique per version)
        String documentKey = generateDocumentKey(document);



        return jwtExtactor.getUserInfo()
                .onErrorResume(_ -> Mono.just((T) OnlyOfficeUserInfo.builder().id(UserInfoService.ANONYMOUS_USER).name(UserInfoService.ANONYMOUS_USER).email(UserInfoService.ANONYMOUS_USER).build()))
                .flatMap(userInfo -> {
                    // Generate access token for document download (includes user info for authentication)
                    String accessToken = jwtService.generateAccessToken(document.getId(), userInfo);

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

                    return Mono.just(new OnlyOfficeConfigResponse(documentServerUrl, apiJsUrl, config, token));

                });


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
        String baseUrl = commonProperties.getApiInternalBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        return baseUrl + "api/v1/documents/" + documentId + "/onlyoffice-download?token=" + accessToken;
    }

    private String buildCallbackUrl(UUID documentId) {
        // URL for OnlyOffice to send save callbacks
        // Use apiBaseUrl which can be configured to host.docker.internal for Docker setups
        String baseUrl = commonProperties.getApiInternalBaseUrl();
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
        userMap.put("id", config.editorConfig().user().getId());
        userMap.put("name", config.editorConfig().user().getName());
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
                .flatMap(document -> downloadAndSaveDocument(document, callback.url()))
                .then();
    }

    private Mono<Document> downloadAndSaveDocument(Document document, String downloadUrl) {
        WebClient webClient = webClientBuilder.build();

        // Stream to temp file while computing checksum - no full file in memory
        return Mono.using(
                // Resource supplier: create temp file
                () -> Files.createTempFile("onlyoffice-", "-" + sanitizeFilename(document.getName())),
                // Resource usage: stream download to temp file, compute checksum, then save
                tempFile -> streamToTempFileWithChecksum(webClient, downloadUrl, tempFile)
                        .flatMap(contentInfo -> {
                            FilePart filePart = new PathFilePart("file", document.getName(), tempFile);
                            return documentService.replaceDocumentContent(document.getId(), filePart, contentInfo);
                        })
                        .doOnSuccess(v -> log.info("Document {} saved from OnlyOffice", document.getId()))
                        .doOnError(e -> {
                            log.error("Failed to save document {} from OnlyOffice: {}", document.getId(), e.getMessage());
                            log.error("Exception while saving document from OnlyOffice", e);
                        }),
                // Cleanup: delete temp file
                tempFile -> {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException e) {
                        log.warn("Failed to delete temp file: {}", tempFile, e);
                    }
                }
        );
    }

    /**
     * Streams content from URL to a temp file while computing checksum in a single pass.
     * Uses DigestOutputStream to compute checksum during write - no buffering of entire file.
     * DataBufferUtils.write() handles buffer lifecycle (release) properly.
     */
    private Mono<ContentInfo> streamToTempFileWithChecksum(WebClient webClient, String downloadUrl, Path tempFile) {
        return Mono.fromCallable(() -> MessageDigest.getInstance(SHA_256))
                .flatMap(digest -> Mono.usingWhen(
                        // Resource: create DigestOutputStream wrapping file output
                        Mono.fromCallable(() -> new DigestOutputStream(Files.newOutputStream(tempFile), digest))
                                .subscribeOn(Schedulers.boundedElastic()),
                        // Use resource: stream download through DigestOutputStream
                        outputStream -> {
                            var dataBufferFlux = webClient.get()
                                    .uri(downloadUrl)
                                    .retrieve()
                                    .bodyToFlux(DataBuffer.class);

                            // DataBufferUtils.write handles buffer release internally
                            return DataBufferUtils.write(dataBufferFlux, outputStream)
                                    .publishOn(Schedulers.boundedElastic())
                                    .then(Mono.fromCallable(() -> {
                                        outputStream.flush();
                                        long length = Files.size(tempFile);
                                        String checksum = HexFormat.of().formatHex(digest.digest());
                                        return new ContentInfo(length, checksum);
                                    }));
                        },
                        // Cleanup on success
                        outputStream -> Mono.fromRunnable(() -> closeQuietly(outputStream))
                                .subscribeOn(Schedulers.boundedElastic()),
                        // Cleanup on error
                        (outputStream, err) -> Mono.fromRunnable(() -> closeQuietly(outputStream))
                                .subscribeOn(Schedulers.boundedElastic()),
                        // Cleanup on cancel
                        outputStream -> Mono.fromRunnable(() -> closeQuietly(outputStream))
                                .subscribeOn(Schedulers.boundedElastic())
                ));
    }

    private void closeQuietly(java.io.OutputStream outputStream) {
        try {
            outputStream.close();
        } catch (IOException e) {
            log.warn("Failed to close output stream", e);
        }
    }

    private String sanitizeFilename(String filename) {
        // Remove characters that are invalid in temp file names
        return filename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }



}
