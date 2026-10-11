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
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.security.OnlyOfficeAuthenticationToken;
import org.openfilz.dms.service.*;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.PathFilePart;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

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
    public Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit, String versionId) {
        // Viewing a historical version is always read-only, so request RO access for it.
        boolean wantsEdit = canEdit && versionId == null;
        return documentDAO.findById(documentId, wantsEdit ? AccessType.RW : AccessType.RO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .flatMap(document -> buildEditorConfig(document, canEdit, versionId));
    }

    @Override
    public Mono<Void> handleCallback(UUID documentId, OnlyOfficeCallbackRequest callback) {
        log.info("OnlyOffice callback for document {}: status={}, key={}", documentId, callback.status(), callback.key());

        return authorizeCallback(documentId, callback)
                .then(Mono.defer(() -> {
                    if (callback.shouldSave()) {
                        return saveDocumentFromCallback(documentId, callback);
                    } else if (callback.isError()) {
                        log.error("OnlyOffice error for document {}: status={}", documentId, callback.status());
                    } else {
                        log.debug("OnlyOffice status for document {}: {} (no action needed)", documentId, callback.status());
                    }
                    return Mono.empty();
                }));
    }

    private static final String TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "access";
    private static final String PAYLOAD_CLAIM = "payload";
    private static final String URL_FIELD = "url";

    /**
     * The callback endpoint accepts any token signed with the shared secret, and users hold one:
     * the download access token in their editor config. So before acting, the authenticated
     * token must be (1) bound to the document in the path (an access token for document A
     * replayed on the callback of document B overwrote B) and (2) not a download access token
     * at all ({@code type=access}); the document server's own callback tokens carry the callback
     * body as {@code payload}. When the body carries the document server's {@code token} (a JWT
     * of the body itself), it is verified too, and the download URL it signs must be the one in
     * the body. Fails closed when no OnlyOffice token authenticated the request.
     */
    private Mono<Void> authorizeCallback(UUID documentId, OnlyOfficeCallbackRequest callback) {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .filter(OnlyOfficeAuthenticationToken.class::isInstance)
                .cast(OnlyOfficeAuthenticationToken.class)
                .switchIfEmpty(Mono.error(new OperationForbiddenException("OnlyOffice callback without a document server token")))
                .flatMap(auth -> {
                    if (auth.getDocumentId() == null || !auth.getDocumentId().equals(documentId)) {
                        log.warn("OnlyOffice callback for document {} refused: token is bound to document {}", documentId, auth.getDocumentId());
                        return Mono.error(new OperationForbiddenException("OnlyOffice callback token is not for document " + documentId));
                    }
                    Map<String, Object> claims = jwtService.validateAndDecode(auth.getRawToken());
                    if (claims == null) {
                        return Mono.error(new OperationForbiddenException("Invalid OnlyOffice callback token"));
                    }
                    if (ACCESS_TOKEN_TYPE.equals(claims.get(TYPE_CLAIM))) {
                        log.warn("OnlyOffice callback for document {} refused: authenticated with a download access token", documentId);
                        return Mono.error(new OperationForbiddenException("A download access token cannot drive an OnlyOffice callback"));
                    }
                    if (!signedUrlMatches(claims.get(PAYLOAD_CLAIM), callback.url())) {
                        log.warn("OnlyOffice callback for document {} refused: download URL differs from the one the document server signed", documentId);
                        return Mono.error(new OperationForbiddenException("OnlyOffice callback URL does not match the signed callback"));
                    }
                    if (callback.token() != null && !callback.token().isBlank()) {
                        Map<String, Object> bodyClaims = jwtService.validateAndDecode(callback.token());
                        if (bodyClaims == null) {
                            log.warn("OnlyOffice callback for document {} refused: body token signature invalid", documentId);
                            return Mono.error(new OperationForbiddenException("Invalid OnlyOffice callback body token"));
                        }
                        if (!signedUrlMatches(bodyClaims, callback.url()) || !signedUrlMatches(bodyClaims.get(PAYLOAD_CLAIM), callback.url())) {
                            log.warn("OnlyOffice callback for document {} refused: download URL differs from the one signed in the body token", documentId);
                            return Mono.error(new OperationForbiddenException("OnlyOffice callback URL does not match the signed callback"));
                        }
                    }
                    return Mono.empty();
                });
    }

    /** True unless {@code signed} is a map carrying a {@code url} different from {@code url}. */
    private static boolean signedUrlMatches(Object signed, String url) {
        if (!(signed instanceof Map<?, ?> map) || !(map.get(URL_FIELD) instanceof String signedUrl)) {
            return true;
        }
        return signedUrl.equals(url);
    }

    /**
     * Whether a save callback's download URL may be fetched: http or https, no user info, and a
     * host that is the configured document server's or one of
     * {@code onlyoffice.document-server.allowed-download-hosts}. Allow-list semantics: anything
     * else is refused whatever it resolves to, because the response becomes the document's
     * content and the endpoint is reachable with a user-held token. Only the host is compared:
     * the document server hands back URLs on its own public name, whose port is the public one.
     * Loopback names count as one host ({@code localhost}, {@code 127.x}, {@code ::1}).
     */
    protected boolean isAllowedDownloadUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException | NullPointerException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (uri.getRawUserInfo() != null || uri.getHost() == null) {
            return false;
        }
        return allowedDownloadHosts().contains(normalizeHost(uri.getHost()));
    }

    private Set<String> allowedDownloadHosts() {
        Set<String> hosts = new HashSet<>();
        OnlyOfficeProperties.DocumentServer server = onlyOfficeProperties.getDocumentServer();
        if (server == null) {
            return hosts;
        }
        addHost(hosts, server.getUrl());
        if (server.getAllowedDownloadHosts() != null) {
            server.getAllowedDownloadHosts().forEach(h -> addHost(hosts, h));
        }
        return hosts;
    }

    /** Accepts a URL or a bare host name. */
    private static void addHost(Set<String> hosts, String urlOrHost) {
        if (urlOrHost == null || urlOrHost.isBlank()) {
            return;
        }
        String value = urlOrHost.trim();
        try {
            URI uri = new URI(value.contains("://") ? value : "http://" + value);
            if (uri.getHost() != null) {
                hosts.add(normalizeHost(uri.getHost()));
                return;
            }
        } catch (URISyntaxException ignored) {
            // not a URL: kept as a host name below
        }
        hosts.add(normalizeHost(value));
    }

    private static final String LOOPBACK = "localhost";

    private static String normalizeHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[") && h.endsWith("]")) {
            h = h.substring(1, h.length() - 1);
        }
        if (h.equals(LOOPBACK) || h.startsWith("127.") || h.equals("::1") || h.equals("0:0:0:0:0:0:0:1")) {
            return LOOPBACK;
        }
        return h;
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

    private Mono<OnlyOfficeConfigResponse> buildEditorConfig(Document document, boolean requestedEdit, String versionId) {
        // Viewing a historical version is always read-only. Otherwise subclasses may force a
        // document into read-only mode regardless of the caller's access (see isDocumentReadOnly);
        // the base implementation never restricts.
        final boolean canEdit = versionId == null && requestedEdit && !isDocumentReadOnly(document);
        String documentServerUrl = onlyOfficeProperties.getDocumentServer().getUrl();
        String apiJsUrl = onlyOfficeProperties.getDocumentServer().getApiUrl();

        // Generate document key (unique per version — OnlyOffice caches content by key, so a
        // historical version must get a key distinct from the latest, or it serves stale content)
        String documentKey = generateDocumentKey(document, versionId);



        return jwtExtactor.getUserInfo()
                .onErrorResume(_ -> Mono.just((T) OnlyOfficeUserInfo.builder().id(UserInfoService.ANONYMOUS_USER).name(UserInfoService.ANONYMOUS_USER).email(UserInfoService.ANONYMOUS_USER).build()))
                .flatMap(userInfo -> {
                    // Generate access token for document download (includes user info for authentication)
                    String accessToken = jwtService.generateAccessToken(document.getId(), userInfo);

                    // Build document URL that OnlyOffice can fetch (pinned to versionId when viewing history)
                    String documentUrl = buildDocumentUrl(document.getId(), accessToken, versionId);

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

    /**
     * Hook for subclasses to force a document into OnlyOffice read-only (view) mode regardless of
     * the caller's access level. The base implementation never restricts editing; subclasses may
     * override it to lock specific documents — returning {@code true} opens the editor in "view"
     * mode with {@code permissions.edit=false}.
     */
    protected boolean isDocumentReadOnly(Document document) {
        return false;
    }

    private String generateDocumentKey(Document document, String versionId) {
        // OnlyOffice caches document content by key; a distinct key means a distinct instance.
        // For a specific stored version use a stable version-scoped key so OnlyOffice never
        // serves the latest content in its place. Keys allow [0-9a-zA-Z.=_-], max 128 chars.
        if (versionId != null && !versionId.isBlank()) {
            String safeVersion = versionId.replaceAll("[^0-9a-zA-Z.=_-]", "-");
            String key = document.getId().toString() + "_v_" + safeVersion;
            return key.length() > 128 ? key.substring(0, 128) : key;
        }
        // Key format: documentId_timestamp
        // This ensures a new key after each save, invalidating OnlyOffice cache
        long timestamp = document.getUpdatedAt() != null
                ? document.getUpdatedAt().toEpochSecond()
                : document.getCreatedAt().toEpochSecond();
        return document.getId().toString() + "_" + timestamp;
    }

    private String buildDocumentUrl(UUID documentId, String accessToken, String versionId) {
        // URL for OnlyOffice to download the document
        // Use apiBaseUrl which can be configured to host.docker.internal for Docker setups
        String baseUrl = commonProperties.getApiInternalBaseUrl();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        String url = baseUrl + "api/v1/documents/" + documentId + "/onlyoffice-download?token=" + accessToken;
        if (versionId != null && !versionId.isBlank()) {
            url += "&versionId=" + URLEncoder.encode(versionId, StandardCharsets.UTF_8);
        }
        return url;
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
        if (!isAllowedDownloadUrl(callback.url())) {
            log.warn("OnlyOffice callback for document {} refused: download URL {} is not on the document server host(s) {}",
                    documentId, callback.url(), allowedDownloadHosts());
            return Mono.error(new OperationForbiddenException("OnlyOffice callback download URL is not the document server's"));
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
                            OnlyOfficeProperties.DocumentServer server = onlyOfficeProperties.getDocumentServer();
                            Duration timeout = server != null && server.getDownloadTimeout() != null ? server.getDownloadTimeout() : Duration.ofSeconds(120);
                            long maxBytes = server != null ? server.getMaxDownloadBytes() : 0L;
                            var dataBufferFlux = webClient.get()
                                    .uri(downloadUrl)
                                    .retrieve()
                                    .bodyToFlux(DataBuffer.class)
                                    .timeout(timeout)
                                    .transform(flux -> capBytes(flux, maxBytes));

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

    /** Fails the download once more than {@code maxBytes} came in (0 = no cap); the offending buffer is released. */
    static Flux<DataBuffer> capBytes(Flux<DataBuffer> flux, long maxBytes) {
        if (maxBytes <= 0) {
            return flux;
        }
        AtomicLong seen = new AtomicLong();
        return flux.handle((buffer, sink) -> {
            long total = seen.addAndGet(buffer.readableByteCount());
            if (total > maxBytes) {
                DataBufferUtils.release(buffer);
                sink.error(new FileSizeExceededException(total, maxBytes));
            } else {
                sink.next(buffer);
            }
        });
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
