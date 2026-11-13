package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.audit.EditorConflictAudit;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.DocumentLanguageInfo;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.IUserInfo;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse.*;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.DocumentNotFoundException;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.InstanceQuotaExceededException;
import org.openfilz.dms.exception.OperationForbiddenException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.exception.VirusFoundException;
import org.openfilz.dms.exception.VersioningDisabledException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.security.OnlyOfficeAuthenticationToken;
import org.openfilz.dms.service.*;
import org.openfilz.dms.service.language.DocumentLanguages;
import org.openfilz.dms.service.language.DocxLanguage;
import org.openfilz.dms.service.language.TextLanguageDetector;
import org.openfilz.dms.service.onlyoffice.EditorConflictNotifier;
import org.openfilz.dms.service.onlyoffice.EditorSessionStore;
import org.openfilz.dms.service.onlyoffice.EditorSessionStore.EditorSession;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.PathFilePart;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

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

    /** Reads document content for the language inspection; set by Spring, so subclass constructors stay unchanged. */
    private StorageService storageService;

    /** Above this size a document's language is not inspected (the whole file is copied to read it). */
    static final long MAX_LANGUAGE_INSPECTION_BYTES = 50L * 1024 * 1024;

    @Autowired
    public void setStorageService(StorageService storageService) {
        this.storageService = storageService;
    }

    /** What each editor session started from; set by Spring. Null (direct construction) saves as if no session were known. */
    private EditorSessionStore editorSessions;
    private DocumentVersionService versionService;
    private AuditService auditService;
    private EditorConflictNotifier conflictNotifier;

    @Autowired
    public void setEditorSessionCollaborators(EditorSessionStore editorSessions, DocumentVersionService versionService,
                                              AuditService auditService, EditorConflictNotifier conflictNotifier) {
        this.editorSessions = editorSessions;
        this.versionService = versionService;
        this.auditService = auditService;
        this.conflictNotifier = conflictNotifier;
    }

    /** Longest stem kept in the name of an editor's copy, so that the date always fits. */
    private static final int MAX_COPY_STEM = 200;

    /** No colon: some file systems refuse one in a name. */
    private static final DateTimeFormatter COPY_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH-mm-ss");

    @Override
    public Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit, String versionId, String lang) {
        // Viewing a historical version is always read-only, so request RO access for it.
        boolean wantsEdit = canEdit && versionId == null;
        return documentDAO.findById(documentId, wantsEdit ? AccessType.RW : AccessType.RO)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .flatMap(document -> buildEditorConfig(document, canEdit, versionId, DocumentLanguages.uiLanguage(lang)));
    }

    @Override
    public Mono<DocumentLanguageInfo> getDocumentLanguage(UUID documentId) {
        return documentDAO.findById(documentId, AccessType.RO)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(document -> {
                    if (!isLanguageSupported(document)) {
                        return Mono.just(DocumentLanguageInfo.unsupported());
                    }
                    Mono<Boolean> editable = documentDAO.findById(documentId, AccessType.RW)
                            .map(rw -> !isDocumentReadOnly(rw))
                            .defaultIfEmpty(false)
                            .onErrorReturn(false);
                    return editable
                            .flatMap(canEdit -> withLocalCopy(document, file -> languageInfo(DocxLanguage.inspect(file), canEdit)))
                            .onErrorResume(IOException.class, e -> {
                                log.warn("Could not read the language of document {}: {}", documentId, e.getMessage());
                                return Mono.just(DocumentLanguageInfo.unsupported());
                            });
                });
    }

    @Override
    public Mono<DocumentLanguageInfo> setDocumentLanguage(UUID documentId, String language) {
        if (!DocumentLanguages.isValidTag(language)) {
            return Mono.error(new IllegalArgumentException(
                    "Invalid language '" + language + "': expected a language and a region, e.g. fr-FR"));
        }
        return documentDAO.findById(documentId, AccessType.RW)
                .switchIfEmpty(Mono.error(new DocumentNotFoundException(documentId)))
                .flatMap(document -> {
                    if (isDocumentReadOnly(document)) {
                        return Mono.error(new OperationForbiddenException("Document " + documentId + " is read-only"));
                    }
                    if (!isLanguageSupported(document)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                                "The language can only be set on a Word (.docx) document"));
                    }
                    return withLocalCopy(document, source -> source)
                            .flatMap(source -> rewriteLanguage(document, source, language)
                                    .doFinally(_ -> deleteQuietly(source.getParent())));
                });
    }

    /**
     * Writes {@code language} into the copy {@code source} and saves it as a new version, through
     * {@code replaceDocumentContent} so versioning, audit, checksum, quota and indexing apply. A
     * document already set to {@code language} is left as it is (no new version).
     */
    private Mono<DocumentLanguageInfo> rewriteLanguage(Document document, Path source, String language) {
        return Mono.fromCallable(() -> {
                    DocxLanguage.Inspection before = DocxLanguage.inspect(source);
                    if (!before.rewritable()) {
                        throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                                "This Word document has no style part to hold a default language");
                    }
                    if (isAlreadySet(before, language)) {
                        return source;
                    }
                    Path target = source.resolveSibling("lang-" + source.getFileName());
                    DocxLanguage.rewrite(source, target, language, before.declaredLanguage());
                    return target;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> result == source
                        ? Mono.just(result)
                        : Mono.fromCallable(() -> Files.size(result)).subscribeOn(Schedulers.boundedElastic())
                        .flatMap(size -> documentService.replaceDocumentContent(document.getId(),
                                new PathFilePart("file", document.getName(), result), new ContentInfo(size, null)))
                        .doOnSuccess(_ -> log.info("Proofing language of document {} set to {}", document.getId(), language))
                        .thenReturn(result))
                .flatMap(result -> Mono.fromCallable(() -> languageInfo(DocxLanguage.inspect(result), true))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * The default is {@code language}, so is the Normal style's when there is one (OnlyOffice drops
     * an {@code en-US} default on save and keeps the style's), and no run carries another variant of it.
     */
    static boolean isAlreadySet(DocxLanguage.Inspection inspection, String language) {
        String base = DocxLanguage.base(language);
        return language.equals(inspection.defaultLanguage())
                && (!inspection.defaultParagraphStyle() || language.equals(inspection.styleLanguage()))
                && inspection.languages().keySet().stream()
                .allMatch(l -> l.equals(language) || !DocxLanguage.base(l).equals(base));
    }

    /** True for the documents whose language {@link DocxLanguage} reads and writes. */
    protected boolean isLanguageSupported(Document document) {
        return "docx".equals(getFileExtension(document.getName()))
                && (document.getSize() == null || document.getSize() <= MAX_LANGUAGE_INSPECTION_BYTES);
    }

    static DocumentLanguageInfo languageInfo(DocxLanguage.Inspection inspection, boolean editable) {
        String declared = inspection.declaredLanguage();
        var detection = TextLanguageDetector.detect(inspection.text());
        String detected = detection.map(d -> DocumentLanguages.tagFor(d.language())).orElse(null);
        boolean suggested = detection.isPresent() && inspection.rewritable()
                && (declared == null || !DocxLanguage.base(declared).equals(detection.get().language()));
        return new DocumentLanguageInfo(true, editable, declared, detected,
                detection.map(TextLanguageDetector.Detection::confidence).orElse(null), suggested);
    }

    /**
     * Copies the document's current content into a new temp directory and applies {@code use} to
     * the copy. The directory is removed afterwards, unless {@code use} returns the copy itself:
     * the caller then owns it and deletes {@code copy.getParent()}.
     */
    private <R> Mono<R> withLocalCopy(Document document, IoFunction<Path, R> use) {
        return storageService.loadFile(document.getStoragePath())
                .flatMap(resource -> Mono.fromCallable(() -> {
                    Path dir = Files.createTempDirectory("onlyoffice-lang-");
                    Path file = dir.resolve("document.docx");
                    try {
                        try (InputStream in = resource.getInputStream()) {
                            Files.copy(in, file);
                        }
                        R result = use.apply(file);
                        if (result != file) {
                            deleteQuietly(dir);
                        }
                        return result;
                    } catch (IOException | RuntimeException e) {
                        deleteQuietly(dir);
                        throw e;
                    }
                }).subscribeOn(Schedulers.boundedElastic()));
    }

    @FunctionalInterface
    private interface IoFunction<T, R> {
        R apply(T t) throws IOException;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    log.warn("Failed to delete temp file {}", p);
                }
            });
        } catch (IOException e) {
            log.warn("Failed to delete temp directory {}", dir);
        }
    }

    @Override
    public Mono<Void> handleCallback(UUID documentId, OnlyOfficeCallbackRequest callback) {
        log.info("OnlyOffice callback for document {}: status={}, key={}", documentId, callback.status(), callback.key());

        return authorizeCallback(documentId, callback)
                .then(Mono.defer(() -> {
                    if (callback.shouldSave()) {
                        return saveDocumentFromCallback(documentId, callback);
                    } else if (callback.status() == OnlyOfficeCallbackRequest.Status.CLOSED) {
                        return closeSession(callback);
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

    private Mono<OnlyOfficeConfigResponse> buildEditorConfig(Document document, boolean requestedEdit, String versionId, String lang) {
        // Viewing a historical version is always read-only. Otherwise subclasses may force a
        // document into read-only mode regardless of the caller's access (see isDocumentReadOnly);
        // the base implementation never restricts.
        final boolean canEdit = versionId == null && requestedEdit && !isDocumentReadOnly(document);
        String documentServerUrl = onlyOfficeProperties.getDocumentServer().getUrl();
        String apiJsUrl = onlyOfficeProperties.getDocumentServer().getApiUrl();

        // Generate document key (unique per version — OnlyOffice caches content by key, so a
        // historical version must get a key distinct from the latest, or it serves stale content)
        String documentKey = generateDocumentKey(document, versionId);

        return recordEditorSession(document, documentKey, canEdit).then(jwtExtactor.getUserInfo()
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
                            lang,
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

                }));
    }

    /**
     * Notes what an editing session starts from. Only an editing session can save. A failure here
     * must not stop the editor from opening: that session's saves then behave as if it had no row.
     */
    private Mono<Void> recordEditorSession(Document document, String key, boolean canEdit) {
        if (!canEdit || editorSessions == null) {
            return Mono.empty();
        }
        return contentMarker(document)
                .flatMap(marker -> editorSessions.opened(key, document.getId(), marker))
                .onErrorResume(e -> {
                    log.warn("Could not record the OnlyOffice session of document {}: {}", document.getId(), e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Identifies a document's current content: its latest storage version, or where versioning is
     * off its storage path, which each content replacement changes. Never {@code updated_at}: a
     * rename, a move or a metadata change moves it, and none of those is a conflict for an editor.
     */
    private Mono<String> contentMarker(Document document) {
        Mono<String> version = versionService == null ? Mono.empty()
                : versionService.listVersions(document.getId()).collectList()
                // The version the storage flags as current, not the first of a list sorted on a date two writes can share
                .flatMap(versions -> Mono.justOrEmpty(versions.stream().filter(DocumentVersionInfo::latest).findFirst()
                        .or(() -> versions.stream().findFirst())))
                .map(v -> "version:" + v.versionId())
                .onErrorResume(VersioningDisabledException.class, _ -> Mono.empty());
        return version.switchIfEmpty(Mono.fromSupplier(() -> "path:" + document.getStoragePath()));
    }

    private Mono<Void> closeSession(OnlyOfficeCallbackRequest callback) {
        if (editorSessions == null || callback.key() == null) {
            return Mono.empty();
        }
        return editorSessions.closed(callback.key());
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
                .flatMap(document -> downloadAndSaveDocument(document, callback))
                .then();
    }

    private Mono<Void> downloadAndSaveDocument(Document document, OnlyOfficeCallbackRequest callback) {
        String downloadUrl = callback.url();
        WebClient webClient = webClientBuilder.build();

        // Stream to temp file while computing checksum - no full file in memory
        return Mono.using(
                // Resource supplier: create temp file
                () -> Files.createTempFile("onlyoffice-", "-" + sanitizeFilename(document.getName())),
                // Resource usage: stream download to temp file, compute checksum, then save
                tempFile -> streamToTempFileWithChecksum(webClient, downloadUrl, tempFile)
                        .flatMap(contentInfo -> saveDownloaded(document.getId(), tempFile, contentInfo, callback))
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
     * Saves what the editor produced, once downloaded, so the comparison is as close to the write
     * as it can be. The session's work replaces the current content only if that is the content the
     * session started from; otherwise it goes to a copy and the current content stays.
     */
    private Mono<Void> saveDownloaded(UUID documentId, Path file, ContentInfo contentInfo, OnlyOfficeCallbackRequest callback) {
        Mono<Optional<EditorSession>> session = editorSessions == null || callback.key() == null
                ? Mono.just(Optional.empty())
                : editorSessions.find(callback.key(), documentId).map(Optional::of).defaultIfEmpty(Optional.empty());
        return documentDAO.findById(documentId, AccessType.RW)
                .switchIfEmpty(Mono.error(new IllegalArgumentException("Document not found: " + documentId)))
                .flatMap(current -> session.flatMap(known -> {
                    if (known.isEmpty()) {
                        // Opened before sessions were recorded, or silent for too long: nothing to compare with
                        log.warn("OnlyOffice save of document {} with no known session (key {}): saved without a conflict check",
                                documentId, callback.key());
                        return replaceContent(current, file, contentInfo).then();
                    }
                    return contentMarker(current).flatMap(marker -> marker.equals(known.get().baseMarker())
                            ? replaceContent(current, file, contentInfo)
                                    .flatMap(saved -> contentMarker(saved))
                                    .flatMap(moved -> editorSessions.moveBase(callback.key(), moved))
                            : saveConflictCopy(current, known.get(), file, contentInfo, callback));
                }))
                .then(callback.status() == OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE ? closeSession(callback) : Mono.empty());
    }

    private Mono<Document> replaceContent(Document document, Path file, ContentInfo contentInfo) {
        FilePart filePart = new PathFilePart("file", document.getName(), file);
        return documentService.replaceDocumentContent(document.getId(), filePart, contentInfo);
    }

    /**
     * The document changed during this session: its work goes to a document next to it, and neither
     * side is lost. Later saves of the session update that copy. When the copy is refused (WORM,
     * quota, no right on the folder) the failure is audited and the current content stays; the
     * document server is answered an error, never "saved": it holds the only copy of the work, and
     * an error makes it keep that file and tell the person in the editor. Any other failure
     * propagates as it is, unaudited: a storage or database failure passes.
     */
    private Mono<Void> saveConflictCopy(Document current, EditorSession session, Path file, ContentInfo contentInfo,
                                        OnlyOfficeCallbackRequest callback) {
        log.warn("OnlyOffice save of document {} did not start from its current content (key {}): saving a copy",
                current.getId(), session.key());
        return existingCopy(session)
                .flatMap(copy -> replaceContent(copy, file, contentInfo).then(Mono.just(true)))
                .switchIfEmpty(Mono.defer(() -> newCopy(current, session, file, contentInfo, callback).thenReturn(true)))
                .then()
                .onErrorResume(AbstractOnlyOfficeService::isFinalRefusal, e -> {
                    log.error("The copy of the OnlyOffice save of document {} was refused: {}", current.getId(), e.getMessage());
                    return auditConflict(current.getId(), new EditorConflictAudit(session.key(), null, null, e.getMessage()))
                            .onErrorResume(audit -> {
                                log.warn("Could not audit the refused copy of document {}: {}", current.getId(), audit.getMessage());
                                return Mono.empty();
                            })
                            .then(Mono.error(new ConflictCopyRefusedException(e)));
                });
    }

    /**
     * The copy of a diverged session's work was refused for good. Its own type, and not the refusal
     * itself: the callback endpoint answers 403 on a forbidden operation, which there means a
     * callback that is not the document server's. This one must answer {@code error: 1}.
     */
    static final class ConflictCopyRefusedException extends RuntimeException {
        ConflictCopyRefusedException(Throwable refusal) {
            super("The copy of the editor's work was refused: " + refusal.getMessage(), refusal);
        }
    }

    /**
     * A refusal a retry would meet again: the rule (WORM, rights), a size or quota limit, the
     * antivirus. These are audited, the others are not. Named one by one: the common parent of
     * these exceptions also covers storage, index and audit failures, which pass, and each of those
     * would leave an entry saying the copy was refused when it was only late.
     */
    private static boolean isFinalRefusal(Throwable e) {
        return e instanceof OperationForbiddenException || e instanceof AccessDeniedException
                || e instanceof FileSizeExceededException || e instanceof UserQuotaExceededException
                || e instanceof InstanceQuotaExceededException || e instanceof VirusFoundException;
    }

    /** The copy an earlier save of this session made, if it is still there. */
    private Mono<Document> existingCopy(EditorSession session) {
        if (session.conflictCopy() == null) {
            return Mono.empty();
        }
        return documentDAO.findById(session.conflictCopy(), AccessType.RW)
                .onErrorResume(DocumentNotFoundException.class, _ -> Mono.empty());
    }

    private Mono<Void> newCopy(Document current, EditorSession session, Path file, ContentInfo contentInfo,
                               OnlyOfficeCallbackRequest callback) {
        String name = conflictCopyName(current.getName(), OffsetDateTime.now(ZoneOffset.UTC));
        return documentService.uploadDocument(new PathFilePart("file", name, file), contentInfo.length(),
                        current.getParentId(), null, Boolean.FALSE)
                .flatMap(copy -> {
                    if (copy.id() == null) {
                        return Mono.error(new OperationForbiddenException(copy.errorMessage() != null
                                ? copy.errorMessage() : "The copy was not created"));
                    }
                    return editorSessions.conflictCopy(session.key(), copy.id())
                            .then(auditConflict(current.getId(), new EditorConflictAudit(session.key(), copy.id(), name, null)))
                            .then(notifyConflict(current.getId(), copy.id(), name, callback));
                });
    }

    private Mono<Void> auditConflict(UUID documentId, EditorConflictAudit details) {
        return auditService == null ? Mono.empty()
                : auditService.logAction(AuditAction.ONLYOFFICE_SAVE_CONFLICT, DocumentType.FILE, documentId, details);
    }

    /** The copy exists and is audited by now: a notification that fails must not fail the save. */
    private Mono<Void> notifyConflict(UUID documentId, UUID copyId, String name, OnlyOfficeCallbackRequest callback) {
        if (conflictNotifier == null) {
            return Mono.empty();
        }
        return conflictNotifier.savedAsCopy(documentId, copyId, name, callback.users())
                .onErrorResume(e -> {
                    log.warn("Could not notify the editors of document {} about their copy: {}", documentId, e.getMessage());
                    return Mono.empty();
                });
    }

    /** {@code report.docx} gives {@code report (editor copy 2026-11-05 23-19-03).docx}. */
    static String conflictCopyName(String name, OffsetDateTime when) {
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        if (stem.length() > MAX_COPY_STEM) {
            stem = stem.substring(0, MAX_COPY_STEM);
        }
        return "%s (editor copy %s)%s".formatted(stem, COPY_DATE.format(when), extension);
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
