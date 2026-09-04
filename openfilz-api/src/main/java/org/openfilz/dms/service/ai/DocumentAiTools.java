package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.dto.request.DeleteMetadataRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.request.SearchMetadataRequest;
import org.openfilz.dms.dto.request.UpdateMetadataRequest;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.security.DownloadTokenService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.IndexService;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.openfilz.dms.dto.response.DocumentInsightView;
import org.springframework.lang.Nullable;
import tools.jackson.databind.json.JsonMapper;

import java.util.Arrays;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.io.Resource;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * AI tool functions that the LLM can invoke via function calling.
 * These wrap existing OpenFilz services and expose them as callable tools
 * for the AI assistant to perform actions on behalf of the user.
 * <p>
 * Each tool registers discovered documents in a thread-local registry.
 * After the AI generates its response, the service post-processes the text
 * to replace known document names with {@code [[doc:id:parentId:type:name]]} markers
 * that the frontend renders as clickable links.
 */
@Slf4j
@Component
@Lazy
public class DocumentAiTools {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AiDocumentQueryService queryService;
    /**
     * Model backing the vision tool ({@code describeImage}). Not final: when a chat request fails
     * over to another model (see {@code AiFallbackChain}), {@link #rebindChatModel} repoints the
     * vision tool at the model that actually answered, so a benched model is not still called
     * from inside a tool.
     * <p>
     * Nullable since the MCP front-end: an MCP server exposes these same tools to an external
     * agent that brings its own model, so a deployment can serve tools with no OpenFilz-side chat
     * model configured at all. {@code describeImage} is the only consumer and degrades explicitly
     * when this is null; the chat pipeline always passes a real model.
     */
    private ChatModel chatModel;
    private final AiAccessPolicy accessPolicy;


    /**
     * Role gate for the <em>kind</em> of operation a tool performs. Orthogonal to
     * {@link AiAccessPolicy}, which gates <em>which documents</em> it may touch; a tool call must
     * satisfy both. Tools never pass through the HTTP security chain, so without this a READER
     * could write through the chat assistant or {@code /mcp} what the same token is refused over
     * REST.
     */
    private final AiToolRolePolicy rolePolicy;

    /** Document versioning (MinIO/S3 version list + restore). Always a bean — a no-op impl is
     *  selected at runtime when versioning is off, so this is safe to inject unconditionally. */
    private final DocumentVersionService versionService;

    /** Public API base URL, for building the download link downloadDocument hands back. */
    private final CommonProperties commonProperties;

    /**
     * Signed download links ({@code ?token=…}): when the feature is on, the download URL is
     * minted for the requesting user and clickable without a bearer header — the only form of
     * the link a chat transcript can actually use. Null-tolerated for direct unit construction.
     */
    private final DownloadTokenService downloadTokenService;

    /** Audit trail, for {@code getDocumentActivity}. Null-tolerated for direct unit construction. */
    private final AuditService auditService;

    /**
     * The full-text index when full-text search is active, null otherwise: {@code readDocumentContent}
     * serves the text extracted at upload from it instead of downloading the file and running Tika again.
     */
    private final IndexService indexService;

    /** Document insights (file metadata + AI-derived category / summary), shown by getMetadata. Null-tolerated. */
    private final DocumentInsightStore insightStore;

    /** For parsing the metadata-map tool arguments, which arrive as a JSON object string. */
    private static final JsonMapper JSON = JsonMapper.builder().build();
    /**
     * The single constructor, written out rather than generated by Lombok.
     * <p>
     * {@code chatModel} used to be kept in Lombok's {@code @RequiredArgsConstructor} by a
     * {@code @NonNull} marker, which also rejected null at construction. The MCP front-end needs
     * to build a tools instance with no chat model at all, so the marker is gone — and with it
     * Lombok's ability to generate this constructor, since the field is neither final nor
     * {@code @NonNull}. Declaring it explicitly also keeps Spring unambiguous: two candidate
     * constructors and no {@code @Autowired} would leave it looking for a default one.
     */
    public DocumentAiTools(DocumentService documentService,
                           DocumentRepository documentRepository,
                           StorageService storageService,
                           AiDocumentQueryService queryService,
                           ChatModel chatModel,
                           AiAccessPolicy accessPolicy,
                           AiToolRolePolicy rolePolicy,
                           DocumentVersionService versionService,
                           CommonProperties commonProperties,
                           DownloadTokenService downloadTokenService,
                           @Nullable AuditService auditService,
                           @Nullable IndexService indexService,
                           @Nullable DocumentInsightStore insightStore) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.queryService = queryService;
        this.chatModel = chatModel;
        this.accessPolicy = accessPolicy;
        this.rolePolicy = rolePolicy;
        this.versionService = versionService;
        this.commonProperties = commonProperties;
        this.downloadTokenService = downloadTokenService;
        this.auditService = auditService;
        this.indexService = indexService;
        this.insightStore = insightStore;
    }

    /**
     * The requesting user, set per request by {@link DocumentAiToolsFactory}. Every document
     * the tools surface or act on is checked against {@link AiAccessPolicy} for this user.
     * Null (singleton/test usage) only ever meets the permit-all core policy.
     */
    private String userEmail;

    /**
     * The requesting user's Authentication, re-established on every blocking tool
     * subscription so security-context-based enforcement in extension layers (secure DAO
     * overrides) applies inside tool calls. Tool threads have no ambient Reactor context —
     * a plain {@code .block()} would silently run without the caller's identity.
     */
    private Authentication authentication;

    /** Bind the tools instance to the requesting user (fluent, used by the factory). */
    public DocumentAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    /** Subscribe with the requesting user's Authentication in the Reactor context, then block. */
    private <T> T blockWithAuth(Mono<T> mono) {
        return (authentication != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                : mono).block();
    }

    /**
     * Refusal message when the caller's roles do not permit {@code capability}, or {@code null}
     * when they do. Every tool calls this first, before touching any service.
     * <p>
     * Returns a message rather than throwing: a tool result is how both front-ends report a
     * refusal to the model, and an exception would surface as {@code isError} with a stack trace
     * the agent cannot act on.
     */
    private String denyIfNotAllowed(String toolName, ToolCapability capability) {
        if (rolePolicy == null || rolePolicy.isAllowed(authentication, capability)) {
            return null;
        }
        log.warn("[AI-TOOL] {} refused: caller lacks the role for {}", toolName, capability);
        return toolResult(toolName, "Not permitted: your OpenFilz role does not allow this "
                + "operation (" + capability + "). Ask an administrator for the required role.");
    }

    /** Can the requesting user see this document? Root (null) is always visible. */
    private boolean canRead(UUID documentId) {
        return documentId == null || Boolean.TRUE.equals(accessPolicy.canRead(documentId, userEmail).block());
    }

    /** Can the requesting user modify this document / create content inside this folder? */
    private boolean canModify(UUID documentId) {
        return Boolean.TRUE.equals(accessPolicy.canModify(documentId, userEmail).block());
    }

    /** Can the requesting user create content inside the given folder (null = root)? */
    private boolean canCreateIn(UUID parentId) {
        return parentId == null
                ? Boolean.TRUE.equals(accessPolicy.canCreateAtRoot(userEmail).block())
                : canModify(parentId);
    }

    /** Image MIME types supported for direct vision analysis. */
    private static final List<String> VISION_MIME_TYPES = List.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/bmp", "image/tiff"
    );

    /** MIME types that can be analyzed (images directly, PDFs via page rendering). */
    private static final String PDF_MIME_TYPE = "application/pdf";

    /** Maximum number of PDF pages to analyze with vision (to avoid huge cost/latency). */
    private static final int MAX_PDF_PAGES_FOR_VISION = 5;

    /**
     * Registry of documents discovered during tool calls in the current conversation turn.
     * Keyed by document name for fast lookup during post-processing.
     * Cleared before each new chat message.
     */
    private final Map<String, DocRef> documentRegistry = new ConcurrentHashMap<>();

    public record DocRef(UUID id, UUID parentId, String type, String name) {}

    /** Sentinel folder id for the root level (matches the parentId encoding in doc markers). */
    public static final String ROOT_FOLDER_ID = "root";

    /**
     * Folders whose direct content was modified by tool calls in the current turn
     * ({@link #ROOT_FOLDER_ID} for the root level). Sent to the frontend with the DONE
     * event so it can refresh the file explorer only when the displayed folder is affected.
     */
    private final Set<String> modifiedFolders = ConcurrentHashMap.newKeySet();

    private void recordFolderModified(UUID folderId) {
        modifiedFolders.add(folderId != null ? folderId.toString() : ROOT_FOLDER_ID);
    }

    /** Get the folders modified during the current turn (for the DONE event). */
    public Set<String> getModifiedFolders() {
        return modifiedFolders;
    }

    /**
     * Human-readable log of the mutating actions performed during this turn (create / write / move /
     * rename). Consumed by {@code AiChatServiceImpl} to report partial success when the model fails
     * to produce a summary after those side effects have already committed.
     */
    private final List<String> performedActions = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void recordAction(String description) {
        performedActions.add(description);
    }

    public List<String> getPerformedActions() {
        return performedActions;
    }

    private void register(UUID id, UUID parentId, String type, String name) {
        documentRegistry.put(name, new DocRef(id, parentId, type, name));
    }

    private void register(Document doc) {
        register(doc.getId(), doc.getParentId(), doc.getType().name(), doc.getName());
    }

    /** Log and return tool result. */
    private String toolResult(String toolName, String result) {
        log.debug("[AI-TOOL] {} result: {}", toolName, result.length() > 300
                ? result.substring(0, 300) + "... (" + result.length() + " chars)" : result);
        return result;
    }

    /** Safely parse a UUID string, returning null for null/blank/invalid values. */
    private UUID parseUuid(String value) {
        if (value == null || value.isBlank() || "null".equalsIgnoreCase(value)) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID '{}', treating as null", value);
            return null;
        }
    }

    /**
     * Repoint the vision tool at another model after a chat failover.
     * <p>
     * Safe to mutate: instances are created per request by {@code DocumentAiToolsFactory}, and
     * failover happens between streaming attempts on that single request — never concurrently
     * with a tool call.
     */
    public void rebindChatModel(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** Clear the registry before each new user message. */
    public void clearRegistry() {
        documentRegistry.clear();
        modifiedFolders.clear();
        performedActions.clear();
    }

    /** Get all registered documents (for post-processing). */
    public Map<String, DocRef> getRegistry() {
        return documentRegistry;
    }

    /** Patterns to strip UUID references the LLM might include */
    private static final Pattern UUID_LABEL_PATTERN = Pattern.compile(
            "\\s*\\(?(?:id|ID|Id|UUID|uuid|Id:|ID:|with (?:the )?(?:UUID|ID|id))\\s*[:=]?\\s*[a-f0-9-]{36}\\)?");
    private static final Pattern RAW_UUID_PATTERN = Pattern.compile(
            "\\b[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}\\b");

    /** Matches a complete [[doc:...]] marker — well-formed or mangled (empty id, stripped UUID) — capturing the plain name. */
    private static final Pattern DOC_MARKER_PATTERN = Pattern.compile(
            "\\[\\[doc:[^\\[\\]]*?(?:FILE|FOLDER):([^\\[\\]]+?)]]");

    /**
     * Collapse any [[doc:...]] markers in the text back to the plain document name.
     * The LLM sees enriched assistant messages in the conversation history and mimics the
     * marker syntax in new responses; left as-is, the UUID-stripping and name-replacement
     * passes in {@link #enrichWithDocLinks(String)} mangle those markers into nested garbage
     * such as {@code [[doc::root:FILE:[[doc:...]]]]}. Applied repeatedly to unwrap nesting.
     */
    public static String stripDocMarkers(String text) {
        if (text == null || text.isEmpty()) return text;
        String result = text;
        String previous;
        do {
            previous = result;
            result = DOC_MARKER_PATTERN.matcher(result).replaceAll("$1");
        } while (!result.equals(previous));
        return result;
    }

    /**
     * Post-process AI response text:
     * 1. Collapse any marker syntax the LLM mimicked from history back to plain names
     * 2. Strip any leftover UUID references the LLM included
     * 3. Replace known document names with [[doc:...]] markers
     */
    public String enrichWithDocLinks(String text) {
        if (text == null || documentRegistry.isEmpty()) return text;

        // Sort by name length descending to replace longest matches first
        var sortedRefs = documentRegistry.values().stream()
                .sorted((a, b) -> b.name().length() - a.name().length())
                .toList();

        // First collapse LLM-emitted markers to plain names, then strip all UUID references
        String result = stripDocMarkers(text);
        result = UUID_LABEL_PATTERN.matcher(result).replaceAll("");
        result = RAW_UUID_PATTERN.matcher(result).replaceAll("");
        result = result.replaceAll("\\(\\s*\\)", "").replaceAll("\\s{2,}", " ");

        // Then replace known document names with [[doc:...]] markers
        // Use a placeholder to prevent double-replacement (name inside marker gets replaced again)
        int placeholderIndex = 0;
        var placeholders = new java.util.LinkedHashMap<String, String>();

        for (var ref : sortedRefs) {
            String marker = "[[doc:%s:%s:%s:%s]]".formatted(
                    ref.id(), ref.parentId() != null ? ref.parentId() : "root", ref.type(), ref.name());
            String placeholder = "\u0000DOC" + (placeholderIndex++) + "\u0000";
            placeholders.put(placeholder, marker);

            // Replace wrapped variants the LLM might use (including RAG context format)
            result = result.replace("[Document: " + ref.name() + "]", placeholder);
            result = result.replace("[Document:" + ref.name() + "]", placeholder);
            result = result.replace("[" + ref.name() + "]", placeholder);
            result = result.replace("(" + ref.name() + ")", placeholder);
            result = result.replace("\"" + ref.name() + "\"", placeholder);
            result = result.replace("`" + ref.name() + "`", placeholder);
            // Then plain name
            result = result.replace(ref.name(), placeholder);
        }

        // Now swap placeholders with actual markers (no risk of double-replacement)
        for (var entry : placeholders.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }

        return result;
    }

    @Tool(description = "Query documents with filtering, sorting, and pagination. Use this to: list folder contents, search files by name, find recent files, count documents, get document details. This is the main tool for finding documents.")
    public String queryDocuments(
            @ToolParam(required = false, description = "Folder name to list contents of, or null for root folder, or 'all' to search across all folders") String folder,
            @ToolParam(required = false, description = "Filter by name (partial match, case-insensitive). Use this to search for files.") String nameLike,
            @ToolParam(required = false, description = "Filter by type: FILE, FOLDER, or null for both") String type,
            @ToolParam(required = false, description = "Sort by field: name, createdAt, updatedAt, size. Default: updatedAt") String sortBy,
            @ToolParam(required = false, description = "Sort order: ASC or DESC. Default: DESC") String sortOrder,
            @ToolParam(required = false, description = "Max results to return (1-50). Default: 10") Integer pageSize,
            @ToolParam(required = false, description = "Set to true to only return the count, not the documents") Boolean countOnly
    ) {
        String roleDenial = denyIfNotAllowed("queryDocuments", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] queryDocuments called: folder='{}', nameLike='{}', type='{}', sort={}:{}, pageSize={}, countOnly={}",
                folder, nameLike, type, sortBy, sortOrder, pageSize, countOnly);
        try {
            // Resolve folder name to UUID
            // Default: search across all folders unless a specific folder is named
            UUID folderId = null;
            boolean searchAllFolders = folder == null || folder.isBlank() || "null".equalsIgnoreCase(folder) || "all".equalsIgnoreCase(folder);
            if (!searchAllFolders) {
                if ("root".equalsIgnoreCase(folder)) {
                    searchAllFolders = false; // explicit root = only root level
                    folderId = null;
                } else {
                    folderId = resolveToId(folder);
                    if (folderId == null) {
                        return toolResult("queryDocuments", "No folder named '%s' found.".formatted(folder));
                    }
                    searchAllFolders = false;
                }
            }

            // Build request
            DocumentType docType = null;
            if (type != null && !type.isBlank() && !"null".equalsIgnoreCase(type)) {
                try { docType = DocumentType.valueOf(type.toUpperCase()); } catch (Exception ignored) {}
            }

            SortOrder order = SortOrder.DESC;
            if (sortOrder != null && "ASC".equalsIgnoreCase(sortOrder)) order = SortOrder.ASC;

            int size = (pageSize != null && pageSize > 0 && pageSize <= 50) ? pageSize : 10;
            String sort = (sortBy != null && !sortBy.isBlank()) ? sortBy : "updatedAt";

            var request = new ListFolderRequest(
                    searchAllFolders ? null : folderId,  // null = root or all
                    docType,
                    null,           // contentType
                    null,           // name (exact)
                    nameLike,       // nameLike (partial)
                    null,           // metadata
                    null,           // size
                    null, null,     // createdAt range
                    null, null,     // updatedAt range
                    null,           // createdBy
                    null,           // updatedBy
                    null,           // favorite
                    true,           // active
                    (countOnly != null && countOnly) ? null : new PageCriteria(sort, order, 1, size),
                    searchAllFolders  // recursive — when searching all, ignore parent filter
            );

            // Count only
            if (countOnly != null && countOnly) {
                if (accessPolicy.permitAll()) {
                    long count = queryService.count(request, userEmail);
                    return toolResult("queryDocuments", "Found %d document(s).".formatted(count));
                }
                // Per-document policy in effect: count only what the user can actually see
                // (scan a capped page instead of a raw SQL count that would over-report)
                var countRequest = new ListFolderRequest(
                        searchAllFolders ? null : folderId, docType, null, null, nameLike,
                        null, null, null, null, null, null, null, null, null, true,
                        new PageCriteria(sort, order, 1, 200), searchAllFolders);
                var rows = queryService.query(countRequest, userEmail);
                long count = rows == null ? 0 : rows.stream().filter(r -> canRead(r.id())).count();
                return toolResult("queryDocuments", "Found %d document(s)%s.".formatted(
                        count, rows != null && rows.size() >= 200 ? " (only the first 200 were scanned)" : ""));
            }

            // Query — never surface documents the requesting user cannot read
            var results = queryService.query(request, userEmail);
            if (results == null || results.isEmpty()) {
                return toolResult("queryDocuments", "No documents found.");
            }
            var accessible = results.stream().filter(r -> canRead(r.id())).toList();
            if (accessible.isEmpty()) {
                return toolResult("queryDocuments", "No documents found.");
            }

            // Register all results for doc-link enrichment
            accessible.forEach(r -> register(r.id(), r.parentId(), r.type().name(), r.name()));

            // Format results
            String formatted = accessible.stream()
                    .map(r -> "- [%s] %s (%s, %s)".formatted(
                            r.type().name(),
                            r.name(),
                            r.contentType() != null ? r.contentType() : "unknown type",
                            r.createdAt() != null ? r.createdAt().toLocalDate().toString() : "unknown date"))
                    .collect(Collectors.joining("\n"));

            return toolResult("queryDocuments", "Found %d result(s):\n%s".formatted(accessible.size(), formatted));
        } catch (Exception e) {
            log.error("Error querying documents", e);
            return "Error querying documents: " + e.getMessage();
        }
    }

    @Tool(description = "Write text content to a new file in the document library. Use this when the user asks to save, write, or export text to a file.")
    public String writeFile(
            @ToolParam(description = "The filename to create (e.g., 'summary.md', 'report.txt')") String fileName,
            @ToolParam(description = "The text content to write into the file") String content,
            @ToolParam(required = false, description = "The folder name to save in, or null for root") String folderName
    ) {
        String roleDenial = denyIfNotAllowed("writeFile", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] writeFile called with: file='{}', folder='{}', content={}chars", fileName, folderName, content != null ? content.length() : 0);
        try {
            // Same rule as moveDocuments: an unknown named folder must not silently become root
            UUID parentId = null;
            if (!isRootFolderName(folderName)) {
                parentId = resolveToId(folderName);
                if (parentId == null) {
                    return toolResult("writeFile",
                            "No folder named '%s' exists. Ask the user whether to create it (createFolder), then retry.".formatted(folderName));
                }
            }
            if (!canCreateIn(parentId)) {
                return toolResult("writeFile", "You don't have permission to create files in %s.".formatted(
                        parentId == null ? "the root folder" : "folder '%s'".formatted(folderName)));
            }

            // Create a temporary file with the content
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("ai-write-", "-" + fileName);
            java.nio.file.Files.writeString(tempFile, content != null ? content : "");
            long fileSize = java.nio.file.Files.size(tempFile);

            try {
                // Go through the regular upload pipeline (not a raw repository save) so
                // ownership, audit, checksum, thumbnails, and indexing all apply as if the
                // user had uploaded the file themselves
                var response = blockWithAuth(documentService.uploadDocument(
                        new org.openfilz.dms.utils.PathFilePart("file", fileName, tempFile),
                        fileSize, parentId, null, Boolean.FALSE));

                if (response != null && response.id() != null) {
                    register(response.id(), parentId, DocumentType.FILE.name(), fileName);
                    recordFolderModified(parentId);
                    recordAction("Created file '%s'".formatted(fileName));
                    log.info("[AI-TOOL] writeFile: created '{}' ({} bytes) in folder {}", fileName, fileSize, parentId);
                    return toolResult("writeFile", "File '%s' created successfully.".formatted(fileName));
                }
                return toolResult("writeFile", "Failed to save the file%s.".formatted(
                        response != null && response.errorMessage() != null ? ": " + response.errorMessage() : ""));
            } finally {
                java.nio.file.Files.deleteIfExists(tempFile);
            }
        } catch (Exception e) {
            log.error("Error writing file", e);
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new blank Word, Excel, PowerPoint or plain-text document (an empty Office file the "
            + "user can then edit in the app). Use writeFile instead when you have text content to save.")
    public String createBlankDocument(
            @ToolParam(description = "Name of the new document, without extension (e.g. 'Meeting notes'); the right extension is added") String name,
            @ToolParam(description = "WORD (.docx), EXCEL (.xlsx), POWERPOINT (.pptx) or TEXT (.txt)") String documentType,
            @ToolParam(required = false, description = "The folder name (or id) to create it in, or null for root") String folderName
    ) {
        String roleDenial = denyIfNotAllowed("createBlankDocument", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] createBlankDocument called with: name='{}', type='{}', folder='{}'", name, documentType, folderName);
        try {
            if (name == null || name.isBlank()) {
                return toolResult("createBlankDocument", "A document name is required.");
            }
            org.openfilz.dms.enums.DocumentTemplateType type = parseTemplateType(documentType);
            if (type == null) {
                return toolResult("createBlankDocument",
                        "Unknown document type '%s'. Use WORD, EXCEL, POWERPOINT or TEXT.".formatted(documentType));
            }
            UUID parentId = null;
            if (!isRootFolderName(folderName)) {
                parentId = resolveToId(folderName);
                if (parentId == null) {
                    return toolResult("createBlankDocument",
                            "No folder named '%s' exists. Ask the user whether to create it (createFolder), then retry.".formatted(folderName));
                }
            }
            if (!canCreateIn(parentId)) {
                return toolResult("createBlankDocument", "You don't have permission to create documents in %s.".formatted(
                        parentId == null ? "the root folder" : "folder '%s'".formatted(folderName)));
            }
            var response = blockWithAuth(documentService.createBlankDocument(name.trim(), type, parentId));
            if (response == null || response.id() == null) {
                return toolResult("createBlankDocument", "Failed to create the document%s.".formatted(
                        response != null && response.errorMessage() != null ? ": " + response.errorMessage() : ""));
            }
            String createdName = response.name() != null ? response.name() : name.trim();
            register(response.id(), parentId, DocumentType.FILE.name(), createdName);
            recordFolderModified(parentId);
            recordAction("Created blank %s document '%s'".formatted(type.name().toLowerCase(), createdName));
            log.info("[AI-TOOL] createBlankDocument: created '{}' ({}) in folder {}", createdName, type, parentId);
            return toolResult("createBlankDocument", "Document '%s' created successfully (id %s).".formatted(createdName, response.id()));
        } catch (Exception e) {
            log.error("Error creating blank document", e);
            return "Error creating document: " + e.getMessage();
        }
    }

    private static org.openfilz.dms.enums.DocumentTemplateType parseTemplateType(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase(java.util.Locale.ROOT).replace(".", "");
        return switch (normalized) {
            case "WORD", "DOCX", "DOC", "DOCUMENT" -> org.openfilz.dms.enums.DocumentTemplateType.WORD;
            case "EXCEL", "XLSX", "XLS", "SPREADSHEET", "SHEET" -> org.openfilz.dms.enums.DocumentTemplateType.EXCEL;
            case "POWERPOINT", "PPTX", "PPT", "PRESENTATION", "SLIDES" -> org.openfilz.dms.enums.DocumentTemplateType.POWERPOINT;
            case "TEXT", "TXT", "PLAIN", "PLAINTEXT" -> org.openfilz.dms.enums.DocumentTemplateType.TEXT;
            default -> null;
        };
    }

    @Tool(description = "Create a new folder. Returns the new folder's ID.")
    public String createFolder(
            @ToolParam(description = "Name of the new folder") String name,
            @ToolParam(required = false, description = "UUID or name of the parent folder. Use null for root.") String parentFolderId
    ) {
        String roleDenial = denyIfNotAllowed("createFolder", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] createFolder called with: name='{}', parent='{}'", name, parentFolderId);
        try {
            // An unknown named parent must not silently become root
            UUID parentId = null;
            if (!isRootFolderName(parentFolderId)) {
                parentId = resolveToId(parentFolderId);
                if (parentId == null) {
                    return toolResult("createFolder",
                            "No parent folder '%s' exists. Create it first, or use null for root.".formatted(parentFolderId));
                }
            }
            if (!canCreateIn(parentId)) {
                return toolResult("createFolder", "You don't have permission to create folders in %s.".formatted(
                        parentId == null ? "the root folder" : "folder '%s'".formatted(parentFolderId)));
            }
            var request = new CreateFolderRequest(name, parentId);

            var result = blockWithAuth(documentService.createFolder(request));
            register(result.id(), parentId, DocumentType.FOLDER.name(), name);
            recordFolderModified(parentId);
            recordAction("Created folder '%s'".formatted(name));
            return "Folder '%s' created successfully with ID: %s".formatted(name, result.id());
        } catch (Exception e) {
            log.error("Error creating folder", e);
            return "Error creating folder: " + e.getMessage();
        }
    }

    @Tool(description = "Move files or folders to a different folder. Accepts document/folder names or IDs.")
    public String moveDocuments(
            @ToolParam(description = "Comma-separated list of document or folder names to move") String documentNames,
            @ToolParam(required = false, description = "Name of the target folder (must already exist — create it with createFolder first if needed), or null for root.") String targetFolder
    ) {
        String roleDenial = denyIfNotAllowed("moveDocuments", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] moveDocuments called with: items='{}', target='{}'", documentNames, targetFolder);
        try {
            // Resolve target folder — a named folder that doesn't exist must NOT silently
            // fall back to root (the move would fail or land in the wrong place)
            UUID targetId = null;
            if (!isRootFolderName(targetFolder)) {
                targetId = resolveToId(targetFolder);
                if (targetId == null) {
                    return toolResult("moveDocuments",
                            "No folder named '%s' exists. Ask the user whether to create it (createFolder), then retry the move.".formatted(targetFolder));
                }
            }
            if (!canCreateIn(targetId)) {
                return toolResult("moveDocuments", "You don't have permission to move documents into %s.".formatted(
                        targetId == null ? "the root folder" : "folder '%s'".formatted(targetFolder)));
            }

            // Resolve each item to move — a document the user cannot see behaves like a
            // non-existent one; a visible but non-modifiable one is reported as denied
            List<UUID> fileIds = new ArrayList<>();
            List<UUID> folderIds = new ArrayList<>();
            List<UUID> sourceParents = new ArrayList<>();
            List<String> denied = new ArrayList<>();

            for (String name : documentNames.split(",")) {
                String trimmed = name.trim();
                UUID id = parseUuid(trimmed);
                Document doc = null;

                if (id != null) {
                    doc = canRead(id) ? blockWithAuth(documentRepository.findById(id)) : null;
                } else {
                    // Resolve by name from registry
                    var ref = documentRegistry.get(trimmed);
                    if (ref != null) {
                        id = ref.id();
                        doc = canRead(id) ? blockWithAuth(documentRepository.findById(id)) : null;
                    } else {
                        // Search by name — skip candidates the user cannot see
                        var found = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(trimmed)
                                .collectList().block();
                        if (found != null) {
                            var readable = found.stream().filter(d -> canRead(d.getId())).findFirst();
                            if (readable.isPresent()) {
                                doc = readable.get();
                                id = doc.getId();
                            }
                        }
                    }
                }

                if (doc != null && id != null) {
                    if (!canModify(id)) {
                        denied.add(doc.getName());
                        continue;
                    }
                    sourceParents.add(doc.getParentId());
                    if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                        folderIds.add(id);
                    } else {
                        fileIds.add(id);
                    }
                }
            }

            if (!denied.isEmpty()) {
                return toolResult("moveDocuments",
                        "You don't have permission to move: %s.".formatted(String.join(", ", denied)));
            }

            int moved = 0;
            if (!fileIds.isEmpty()) {
                blockWithAuth(documentService.moveFiles(new MoveRequest(fileIds, targetId, false)));
                moved += fileIds.size();
            }
            if (!folderIds.isEmpty()) {
                blockWithAuth(documentService.moveFolders(new MoveRequest(folderIds, targetId, false)));
                moved += folderIds.size();
            }

            if (moved == 0) return "No matching documents found to move.";
            // Both sides of the move changed content: the target and each source folder
            recordFolderModified(targetId);
            sourceParents.forEach(this::recordFolderModified);
            recordAction("Moved %d item(s) to %s".formatted(moved,
                    isRootFolderName(targetFolder) ? "the root folder" : "'%s'".formatted(targetFolder)));
            return "Successfully moved %d item(s).".formatted(moved);
        } catch (Exception e) {
            log.error("Error moving documents", e);
            return "Error moving documents: " + e.getMessage();
        }
    }

    /** True when the name refers to the root folder (null/blank or a root alias). */
    private boolean isRootFolderName(String name) {
        return name == null || name.isBlank() || "null".equalsIgnoreCase(name)
                || "root".equalsIgnoreCase(name) || "My Folder".equalsIgnoreCase(name);
    }

    /**
     * Resolve a name or UUID string to a UUID, searching the registry and DB if needed.
     * Only ever resolves to documents the requesting user can read — a name or id the
     * user has no access to behaves exactly like a non-existent one.
     */
    /**
     * Resolve a document reference (UUID or name) to an id the caller may read — <b>file or
     * folder</b>. {@link #resolveToId} is folder-biased (its DB fallback matches folders only,
     * which is correct for the folder-argument tools); the document-targeting tools below
     * (metadata, delete, versions, download) use this instead so a file name resolves too.
     */
    private UUID resolveDocumentToId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) {
            return null;
        }
        UUID id = parseUuid(nameOrId);
        if (id != null) {
            return canRead(id) ? id : null;
        }
        var ref = documentRegistry.get(nameOrId);
        if (ref != null) {
            return ref.id();
        }
        var found = blockWithAuth(
                documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(nameOrId).collectList());
        if (found == null) {
            return null;
        }
        return found.stream().map(Document::getId).filter(this::canRead).findFirst().orElse(null);
    }

    private UUID resolveToId(String nameOrId) {
        if (isRootFolderName(nameOrId)) {
            return null; // root folder
        }
        UUID id = parseUuid(nameOrId);
        if (id != null) return canRead(id) ? id : null;
        var ref = documentRegistry.get(nameOrId);
        if (ref != null) return ref.id();
        // Search DB — skip candidates the user cannot see
        var found = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(nameOrId)
                .filter(d -> d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER)
                .collectList().block();
        if (found != null) {
            var readable = found.stream().filter(d -> canRead(d.getId())).findFirst();
            if (readable.isPresent()) {
                register(readable.get());
                return readable.get().getId();
            }
        }
        return null;
    }

    @Tool(description = "Rename a file or folder.")
    public String renameDocument(
            @ToolParam(description = "Name or reference of the document or folder to rename") String documentName,
            @ToolParam(description = "The new name") String newName
    ) {
        String roleDenial = denyIfNotAllowed("renameDocument", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] renameDocument called with: '{}' -> '{}'", documentName, newName);
        try {
            UUID id = resolveDocumentToId(documentName);
            if (id == null) {
                // Also try searching files — skip candidates the user cannot see
                var found = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(documentName)
                        .collectList().block();
                if (found != null) {
                    id = found.stream().filter(d -> canRead(d.getId()))
                            .findFirst().map(Document::getId).orElse(null);
                }
            }
            var renameRequest = new RenameRequest(newName);

            Document doc = id != null ? blockWithAuth(documentRepository.findById(id)) : null;
            if (doc == null) {
                return "Document '%s' not found.".formatted(documentName);
            }
            if (!canModify(doc.getId())) {
                return toolResult("renameDocument",
                        "You don't have permission to rename '%s'.".formatted(doc.getName()));
            }

            if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                blockWithAuth(documentService.renameFolder(id, renameRequest));
            } else {
                blockWithAuth(documentService.renameFile(id, renameRequest));
            }

            recordFolderModified(doc.getParentId());
            recordAction("Renamed '%s' to '%s'".formatted(doc.getName(), newName));
            return "Successfully renamed to '%s'.".formatted(newName);
        } catch (Exception e) {
            log.error("Error renaming document", e);
            return "Error renaming: " + e.getMessage();
        }
    }

    @Tool(description = "Read and extract the text content of a document file. You can optionally specify a folder name to search in.")
    public String readDocumentContent(
            @ToolParam(description = "The name (or part of the name) of the document to read") String documentName,
            @ToolParam(required = false, description = "Optional: the folder name where the document is located. Helps find the right file when the name is ambiguous.") String folderName
    ) {
        String roleDenial = denyIfNotAllowed("readDocumentContent", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] readDocumentContent called with: document='{}', folder='{}'", documentName, folderName);
        try {
            Document doc = null;

            // If a folder is specified, list its contents and find the best match
            if (folderName != null && !folderName.isBlank() && !"null".equalsIgnoreCase(folderName)) {
                UUID folderId = resolveToId(folderName);
                if (folderId == null) {
                    // Try searching for the folder — skip candidates the user cannot see
                    var folders = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(folderName)
                            .filter(d -> d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER)
                            .collectList().block();
                    if (folders != null) {
                        folderId = folders.stream().filter(d -> canRead(d.getId()))
                                .findFirst().map(Document::getId).orElse(null);
                    }
                }
                if (folderId != null) {
                    log.debug("[AI-TOOL] readDocumentContent: searching in folder {} for '{}'", folderId, documentName);
                    // Use queryService to search within folder by name
                    var request = new ListFolderRequest(folderId, DocumentType.FILE, null, null, documentName,
                            null, null, null, null, null, null, null, null, null, true,
                            new PageCriteria("name", SortOrder.ASC, 1, 10), false);
                    var results = queryService.query(request, userEmail);
                    var match = results == null ? java.util.Optional.<UUID>empty()
                            : results.stream().filter(r -> canRead(r.id())).findFirst().map(r -> r.id());
                    if (match.isPresent()) {
                        doc = blockWithAuth(documentRepository.findById(match.get()));
                        log.debug("[AI-TOOL] readDocumentContent: found '{}' in folder", doc != null ? doc.getName() : "null");
                    } else {
                        // No match — list all (accessible) files in folder for the LLM
                        var allInFolder = new ListFolderRequest(folderId, DocumentType.FILE, null, null, null,
                                null, null, null, null, null, null, null, null, null, true,
                                new PageCriteria("name", SortOrder.ASC, 1, 20), false);
                        var allFiles = queryService.query(allInFolder, userEmail);
                        String fileList = allFiles != null ? allFiles.stream()
                                .filter(f -> canRead(f.id()))
                                .map(f -> "- " + f.name()).collect(Collectors.joining("\n")) : "(empty)";
                        return toolResult("readDocumentContent",
                                "No file matching '%s' found in folder '%s'. Files in this folder:\n%s".formatted(documentName, folderName, fileList));
                    }
                }
            }

            // Fallback: resolve by name globally (only ever resolves to accessible documents)
            if (doc == null) {
                UUID id = resolveAnyToId(documentName);
                if (id == null) return toolResult("readDocumentContent", "Document '%s' not found.".formatted(documentName));
                doc = blockWithAuth(documentRepository.findById(id));
            }

            if (doc == null) return toolResult("readDocumentContent", "Document not found.");
            // Final gate before touching content — a document the user cannot read does not exist for them
            if (!canRead(doc.getId())) {
                return toolResult("readDocumentContent", "Document '%s' not found.".formatted(documentName));
            }
            if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                return toolResult("readDocumentContent", "'%s' is a folder. Use listFolder to see its contents.".formatted(documentName));
            }
            if (doc.getActive() != null && !doc.getActive()) {
                return toolResult("readDocumentContent", "Document '%s' has been deleted.".formatted(doc.getName()));
            }

            register(doc);

            // Load the file from storage and extract text with Tika
            // Prefer the text the full-text indexing pass already extracted: no storage download,
            // no second Tika parse. Falls back to the file when the index holds nothing for it.
            String fullText = indexedText(doc);
            if (fullText == null) {
                Resource resource = blockWithAuth(storageService.loadFile(doc.getStoragePath()));
                if (resource == null) return "Could not load the file from storage.";
                fullText = extractText(resource);
            }
            if (fullText == null) {
                return "Could not extract text from this file. It may be a binary or image file.";
            }
            return "Content of '%s':\n\n%s".formatted(doc.getName(), fullText);
        } catch (Exception e) {
            log.error("Error reading document content", e);
            return "Error reading document: " + e.getMessage();
        }
    }

    /** Resolve a name or UUID to any document (file or folder) the requesting user can read. */
    private UUID resolveAnyToId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return null;
        UUID id = parseUuid(nameOrId);
        if (id != null) return canRead(id) ? id : null;
        var ref = documentRegistry.get(nameOrId);
        if (ref != null) return ref.id();
        var found = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(nameOrId)
                .collectList().block();
        if (found != null) {
            var readable = found.stream().filter(d -> canRead(d.getId())).findFirst();
            if (readable.isPresent()) {
                register(readable.get());
                return readable.get().getId();
            }
        }
        return null;
    }

    @Tool(description = "Get the full path (ancestors) of a document from root to its parent folder.")
    public String getDocumentPath(
            @ToolParam(description = "UUID of the document") String documentId
    ) {
        String roleDenial = denyIfNotAllowed("getDocumentPath", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        try {
            UUID id = parseUuid(documentId);
            // A document the user cannot read does not exist for them
            if (id == null || !canRead(id)) {
                return "Document not found.";
            }
            var ancestors = blockWithAuth(documentService.getDocumentAncestors(id)
                    .collectList());

            if (ancestors == null || ancestors.isEmpty()) {
                return "Document is at the root level.";
            }

            return "Path: / " + ancestors.stream()
                    .map(a -> a.name())
                    .collect(Collectors.joining(" / "));
        } catch (Exception e) {
            log.error("Error getting document path", e);
            return "Error getting path: " + e.getMessage();
        }
    }

    @Tool(description = """
            Analyze an image or PDF file stored in the document library using vision capabilities.
            Use this when a user asks to describe, caption, or understand what an image or PDF contains.
            Also use this when the user wants to extract or read text from an image or scanned PDF (OCR).
            You can optionally specify a folder name to narrow down the search.
            The 'task' parameter controls what the model does: 'describe' for a general description/caption,
            'ocr' to extract all visible text from the image or PDF pages, or 'answer' to answer a specific question about it.
            """)
    public String describeImage(
            @ToolParam(description = "The name (or part of the name) of the image to analyze") String imageName,
            @ToolParam(required = false, description = "Optional: the folder name where the image is located") String folderName,
            @ToolParam(required = false, description = "The task: 'describe' for description/caption, 'ocr' to extract text, or 'answer' to answer a specific question") String task,
            @ToolParam(required = false, description = "Optional: the specific question to answer about the image (used when task='answer')") String question
    ) {
        String roleDenial = denyIfNotAllowed("describeImage", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] describeImage called: image='{}', folder='{}', task='{}', question='{}'",
                imageName, folderName, task, question);
        if (chatModel == null) {
            // MCP front-end on a deployment with no OpenFilz-side chat model: the calling agent
            // has its own vision model — tell it so instead of failing with an NPE.
            return toolResult("describeImage",
                    "Vision analysis is unavailable: this OpenFilz server has no AI chat model configured. "
                            + "Read the file with readDocumentContent, or analyse the image with your own model.");
        }
        try {
            Document doc = null;

            // If a folder is specified, search within it first
            if (folderName != null && !folderName.isBlank() && !"null".equalsIgnoreCase(folderName)) {
                UUID folderId = resolveToId(folderName);
                if (folderId == null) {
                    var folders = documentRepository.findTop50ByNameContainingIgnoreCaseAndActiveTrueOrderByNameAsc(folderName)
                            .filter(d -> d.getType() == DocumentType.FOLDER)
                            .collectList().block();
                    if (folders != null) {
                        folderId = folders.stream().filter(d -> canRead(d.getId()))
                                .findFirst().map(Document::getId).orElse(null);
                    }
                }
                if (folderId != null) {
                    var request = new ListFolderRequest(folderId, DocumentType.FILE, null, null, imageName,
                            null, null, null, null, null, null, null, null, null, true,
                            new PageCriteria("name", SortOrder.ASC, 1, 10), false);
                    var results = queryService.query(request, userEmail);
                    var match = results == null ? java.util.Optional.<UUID>empty()
                            : results.stream().filter(r -> canRead(r.id())).findFirst().map(r -> r.id());
                    if (match.isPresent()) {
                        doc = blockWithAuth(documentRepository.findById(match.get()));
                    }
                }
            }

            // Fallback: resolve by name globally (only ever resolves to accessible documents)
            if (doc == null) {
                UUID id = resolveAnyToId(imageName);
                if (id == null) return toolResult("describeImage", "Image '%s' not found.".formatted(imageName));
                doc = blockWithAuth(documentRepository.findById(id));
            }

            if (doc == null) return toolResult("describeImage", "Image not found.");
            // Final gate before touching content — an image the user cannot read does not exist for them
            if (!canRead(doc.getId())) {
                return toolResult("describeImage", "Image '%s' not found.".formatted(imageName));
            }
            if (doc.getType() == DocumentType.FOLDER) {
                return toolResult("describeImage", "'%s' is a folder, not an image.".formatted(imageName));
            }

            // Validate it's a supported image type or PDF
            String contentType = doc.getContentType();
            boolean isPdf = PDF_MIME_TYPE.equals(contentType);
            if (contentType == null || (!VISION_MIME_TYPES.contains(contentType) && !isPdf)) {
                return toolResult("describeImage",
                        "'%s' is not a supported image or PDF (type: %s). Supported images: %s, and application/pdf".formatted(
                                doc.getName(), contentType, VISION_MIME_TYPES));
            }

            register(doc);

            // Load the file from storage
            Resource resource = blockWithAuth(storageService.loadFile(doc.getStoragePath()));
            if (resource == null) return toolResult("describeImage", "Could not load '%s' from storage.".formatted(doc.getName()));

            // Build the prompt depending on the task
            String promptText = switch (task != null ? task.toLowerCase() : "describe") {
                case "ocr" -> "Extract ALL visible text from this image. Return the text exactly as it appears, preserving layout where possible. If there is no text, say so.";
                case "answer" -> (question != null && !question.isBlank())
                        ? question
                        : "Describe this image in detail.";
                default -> "Describe this image in detail. What do you see? Include relevant details about objects, text, colors, layout, and any notable features.";
            };

            String result;
            if (isPdf) {
                result = analyzePdfWithVision(resource, promptText, doc.getName());
            } else {
                MimeType mimeType = MimeType.valueOf(contentType);
                Media imageMedia = new Media(mimeType, resource);

                var userMessage = UserMessage.builder()
                        .text(promptText)
                        .media(imageMedia)
                        .build();

                log.debug("[AI-TOOL] describeImage: sending vision prompt for '{}' (task={})", doc.getName(), task);
                var response = chatModel.call(new Prompt(List.of(userMessage)));
                result = response.getResult().getOutput().getText();
            }

            if (result == null || result.isBlank()) {
                return toolResult("describeImage", "The model could not analyze image '%s'.".formatted(doc.getName()));
            }

            String label = switch (task != null ? task.toLowerCase() : "describe") {
                case "ocr" -> "Text extracted from '%s':\n\n%s";
                case "answer" -> "About '%s':\n\n%s";
                default -> "Description of '%s':\n\n%s";
            };
            return toolResult("describeImage", label.formatted(doc.getName(), result));

        } catch (Exception e) {
            log.error("Error analyzing image", e);
            return toolResult("describeImage", "Error analyzing image: " + e.getMessage());
        }
    }

    @Tool(description = "Get the identity of the OpenFilz user this session acts as, and the "
            + "operations that user's roles allow (read/write/delete documents, shares, comments, "
            + "e-Sign, audit). Read-only and free of side effects — call it before mutating "
            + "operations to confirm which principal you are acting as.")
    public String whoami() {
        // No capability gate beyond IDENTITY_READ (any authenticated caller): the answer is
        // derived from the caller's own token and the same policies that will judge their calls —
        // nothing about any other user or document is revealed.
        Map<String, Object> identity = new java.util.LinkedHashMap<>();
        identity.put("email", userEmail);
        if (authentication instanceof org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken jwt) {
            Object name = jwt.getToken().getClaims().getOrDefault("name",
                    jwt.getToken().getClaims().get("preferred_username"));
            if (name != null) {
                identity.put("name", name.toString());
            }
        }
        identity.put("documentScope", accessPolicy.permitAll()
                ? "all documents — this deployment has no per-document permissions"
                : "per-user — only documents this user owns or that are shared with them");
        Map<String, Boolean> operations = new java.util.LinkedHashMap<>();
        operations.put("readDocuments", allowedForCaller(ToolCapability.DOCUMENT_READ));
        operations.put("writeDocuments", allowedForCaller(ToolCapability.DOCUMENT_WRITE));
        operations.put("deleteDocuments", allowedForCaller(ToolCapability.DOCUMENT_DELETE));
        operations.put("readAuditTrail", allowedForCaller(ToolCapability.AUDIT_READ));
        operations.put("readSignatureStatus", allowedForCaller(ToolCapability.SIGNATURE_READ));
        operations.put("initiateSignatureRequests", allowedForCaller(ToolCapability.SIGNATURE_WRITE));
        operations.put("readShares", allowedForCaller(ToolCapability.SHARE_READ));
        operations.put("manageShares", allowedForCaller(ToolCapability.SHARE_WRITE));
        operations.put("readComments", allowedForCaller(ToolCapability.COMMENT_READ));
        operations.put("writeComments", allowedForCaller(ToolCapability.COMMENT_WRITE));
        identity.put("allowedOperations", operations);
        return toolResult("whoami", JSON.writeValueAsString(identity));
    }

    /** The caller's effective verdict for a capability — the same gate that will judge the call. */
    private boolean allowedForCaller(ToolCapability capability) {
        return rolePolicy == null || rolePolicy.isAllowed(authentication, capability);
    }

    @Tool(description = "Activity (audit trail) of a document or folder: who did what and when (upload, "
            + "download, move, rename, metadata changes, sharing), most recent first. Use it to judge whether "
            + "a document is still in use or who last worked on it. Requires the AUDITOR role.")
    public String getDocumentActivity(
            @ToolParam(description = "Name or id of the document or folder") String document,
            @ToolParam(required = false, description = "Maximum entries to return (default 20, max 100)") Integer limit) {
        String roleDenial = denyIfNotAllowed("getDocumentActivity", ToolCapability.AUDIT_READ);
        if (roleDenial != null) return roleDenial;
        log.debug("[AI-TOOL] getDocumentActivity called with: document='{}', limit={}", document, limit);
        try {
            if (auditService == null) {
                return toolResult("getDocumentActivity", "The audit trail is not available on this deployment.");
            }
            UUID id = resolveAnyToId(document);
            if (id == null) {
                return toolResult("getDocumentActivity", "Document '%s' not found.".formatted(document));
            }
            Document doc = blockWithAuth(documentRepository.findById(id));
            if (doc == null || !canRead(doc.getId())) {
                return toolResult("getDocumentActivity", "Document '%s' not found.".formatted(document));
            }
            register(doc);
            int max = limit == null || limit <= 0 ? 20 : Math.min(limit, 100);
            List<AuditLog> entries = blockWithAuth(auditService.getAuditTrail(id, SortOrder.DESC).take(max).collectList());
            if (entries == null || entries.isEmpty()) {
                return toolResult("getDocumentActivity", "No activity recorded for '%s'.".formatted(doc.getName()));
            }
            long actors = entries.stream().map(AuditLog::username).filter(u -> u != null).distinct().count();
            StringBuilder sb = new StringBuilder();
            sb.append("Activity of '").append(doc.getName()).append("': ").append(entries.size())
                    .append(" most recent entr").append(entries.size() == 1 ? "y" : "ies")
                    .append(entries.size() == max ? " (more may exist)" : "")
                    .append(", ").append(actors).append(" user").append(actors == 1 ? "" : "s")
                    .append(". Format: timestamp | user | action | details\n");
            for (AuditLog entry : entries) {
                sb.append("  ")
                        .append(entry.timestamp() != null ? entry.timestamp().withNano(0).toLocalDateTime() : "?")
                        .append(" | ").append(entry.username() != null ? entry.username() : "?")
                        .append(" | ").append(entry.action())
                        .append(" | ").append(summarizeDetails(entry.details()))
                        .append('\n');
            }
            return toolResult("getDocumentActivity", sb.toString());
        } catch (Exception e) {
            log.error("Error reading document activity", e);
            return "Error reading document activity: " + e.getMessage();
        }
    }

    private static String summarizeDetails(Object details) {
        if (details == null) return "-";
        try {
            String json = JSON.writeValueAsString(details);
            return json.length() > 160 ? json.substring(0, 157) + "..." : json;
        } catch (Exception e) {
            return String.valueOf(details);
        }
    }

    @Tool(description = "Read all metadata (custom properties) of a document or folder as key/value pairs.")
    public String getMetadata(
            @ToolParam(description = "The name or reference of the document or folder") String documentName) {
        String roleDenial = denyIfNotAllowed("getMetadata", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canRead(id)) {
            return toolResult("getMetadata", "No document named '%s' is visible to you.".formatted(documentName));
        }
        Map<String, Object> metadata = blockWithAuth(
                documentService.getDocumentMetadata(id, new SearchMetadataRequest(null)));
        Map<String, Object> insights = insightsOf(id);
        if ((metadata == null || metadata.isEmpty()) && insights.isEmpty()) {
            return toolResult("getMetadata", "'%s' has no metadata.".formatted(documentName));
        }
        StringBuilder sb = new StringBuilder();
        if (metadata != null && !metadata.isEmpty()) {
            sb.append(JSON.writeValueAsString(metadata));
        } else {
            sb.append("'").append(documentName).append("' has no user metadata.");
        }
        if (!insights.isEmpty()) {
            sb.append("\nInsights (derived from the content at upload, read-only): ")
                    .append(JSON.writeValueAsString(insights));
        }
        return toolResult("getMetadata", sb.toString());
    }

    /** The document's insights as a compact map (empty when none, or no store in this deployment). */
    private Map<String, Object> insightsOf(UUID documentId) {
        if (insightStore == null || documentId == null) {
            return Map.of();
        }
        try {
            DocumentInsightView view = blockWithAuth(insightStore.find(documentId).map(DocumentInsightStore::toView));
            return view == null ? Map.of() : DocumentInsightStore.compact(view);
        } catch (Exception e) {
            log.debug("[AI-TOOL] insights lookup failed for {}: {}", documentId, e.getMessage());
            return Map.of();
        }
    }

    @Tool(description = "Add or update metadata (custom properties) on a document or folder. Keys "
            + "not mentioned are left unchanged; existing keys are overwritten.")
    public String updateMetadata(
            @ToolParam(description = "The name or reference of the document or folder") String documentName,
            @ToolParam(description = "The metadata to set, as a JSON object of key/value pairs, "
                    + "e.g. {\"status\":\"reviewed\",\"year\":2026}") String metadataJson) {
        String roleDenial = denyIfNotAllowed("updateMetadata", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canModify(id)) {
            return toolResult("updateMetadata", "You cannot modify '%s'.".formatted(documentName));
        }
        Map<String, Object> metadata = parseJsonObject(metadataJson);
        if (metadata == null || metadata.isEmpty()) {
            return toolResult("updateMetadata", "Provide the metadata to set as a JSON object.");
        }
        blockWithAuth(documentService.updateDocumentMetadata(id, new UpdateMetadataRequest(metadata)));
        return toolResult("updateMetadata", "Updated %d metadata key(s) on '%s'."
                .formatted(metadata.size(), documentName));
    }

    @Tool(description = "Remove metadata keys from a document or folder.")
    public String deleteMetadata(
            @ToolParam(description = "The name or reference of the document or folder") String documentName,
            @ToolParam(description = "Comma-separated list of metadata keys to remove") String keys) {
        String roleDenial = denyIfNotAllowed("deleteMetadata", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canModify(id)) {
            return toolResult("deleteMetadata", "You cannot modify '%s'.".formatted(documentName));
        }
        List<String> keyList = keys == null ? List.of()
                : Arrays.stream(keys.split(",")).map(String::trim).filter(k -> !k.isBlank()).toList();
        if (keyList.isEmpty()) {
            return toolResult("deleteMetadata", "Provide at least one metadata key to remove.");
        }
        blockWithAuth(documentService.deleteDocumentMetadata(id, new DeleteMetadataRequest(keyList)));
        return toolResult("deleteMetadata", "Removed %d metadata key(s) from '%s'."
                .formatted(keyList.size(), documentName));
    }

    @Tool(description = "Find documents by their metadata (custom properties). Returns the matching "
            + "documents the user can see. Use this to answer questions like 'which files are marked "
            + "status=reviewed'.")
    public String searchByMetadata(
            @ToolParam(description = "The metadata to match, as a JSON object of key/value pairs, "
                    + "e.g. {\"status\":\"reviewed\"}") String metadataJson,
            @ToolParam(required = false, description = "Optional: restrict to FILE or FOLDER") String type,
            @ToolParam(required = false, description = "Optional: the folder name to search within") String folderName) {
        String roleDenial = denyIfNotAllowed("searchByMetadata", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        Map<String, Object> criteria = parseJsonObject(metadataJson);
        if (criteria == null || criteria.isEmpty()) {
            return toolResult("searchByMetadata", "Provide the metadata to match as a JSON object.");
        }
        DocumentType docType = parseType(type);
        UUID parentId = (folderName == null || folderName.isBlank()) ? null : resolveToId(folderName);
        List<UUID> ids = blockWithAuth(documentService
                .searchDocumentIdsByMetadata(new SearchByMetadataRequest(null, docType, parentId, null, criteria))
                .filter(this::canRead)
                .collectList());
        if (ids == null || ids.isEmpty()) {
            return toolResult("searchByMetadata", "No documents match that metadata.");
        }
        StringBuilder out = new StringBuilder("Found %d matching document(s):\n".formatted(ids.size()));
        for (UUID id : ids.stream().limit(50).toList()) {
            Document doc = blockWithAuth(documentRepository.findById(id));
            if (doc != null) {
                out.append("- ").append(doc.getName()).append(" (").append(doc.getId()).append(")\n");
            }
        }
        return toolResult("searchByMetadata", out.toString());
    }

    @Tool(description = "Delete a document or folder (moves it to the recycle bin when soft-delete is "
            + "enabled). Deleting a folder removes its contents too.")
    public String deleteDocument(
            @ToolParam(description = "The name or reference of the document or folder to delete") String documentName) {
        String roleDenial = denyIfNotAllowed("deleteDocument", ToolCapability.DOCUMENT_DELETE);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canModify(id)) {
            return toolResult("deleteDocument", "You cannot delete '%s'.".formatted(documentName));
        }
        Document doc = blockWithAuth(documentRepository.findById(id));
        if (doc == null) {
            return toolResult("deleteDocument", "No document named '%s' found.".formatted(documentName));
        }
        DeleteRequest request = new DeleteRequest(List.of(id));
        if (doc.getType() == DocumentType.FOLDER) {
            blockWithAuth(documentService.deleteFolders(request));
            return toolResult("deleteDocument", "Deleted folder '%s' and its contents.".formatted(documentName));
        }
        blockWithAuth(documentService.deleteFiles(request));
        return toolResult("deleteDocument", "Deleted '%s'.".formatted(documentName));
    }

    @Tool(description = "List the stored versions of a document (requires versioned storage). Each "
            + "version has an id you can pass to restoreVersion.")
    public String listVersions(
            @ToolParam(description = "The name or reference of the document") String documentName) {
        String roleDenial = denyIfNotAllowed("listVersions", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canRead(id)) {
            return toolResult("listVersions", "No document named '%s' is visible to you.".formatted(documentName));
        }
        List<DocumentVersionInfo> versions = blockWithAuth(versionService.listVersions(id).collectList());
        if (versions == null || versions.isEmpty()) {
            return toolResult("listVersions", "'%s' has no stored versions (versioned storage may be off)."
                    .formatted(documentName));
        }
        StringBuilder out = new StringBuilder("Versions of '%s' (%d):\n".formatted(documentName, versions.size()));
        for (DocumentVersionInfo v : versions) {
            out.append("- ").append(v.versionId()).append(" — ").append(v.lastModified())
                    .append(" (").append(v.size()).append(" bytes)\n");
        }
        return toolResult("listVersions", out.toString());
    }

    @Tool(description = "Restore a previous version of a document, making it the current content. "
            + "Get the versionId from listVersions first.")
    public String restoreVersion(
            @ToolParam(description = "The name or reference of the document") String documentName,
            @ToolParam(description = "The versionId to restore (from listVersions)") String versionId) {
        String roleDenial = denyIfNotAllowed("restoreVersion", ToolCapability.DOCUMENT_WRITE);
        if (roleDenial != null) return roleDenial;
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canModify(id)) {
            return toolResult("restoreVersion", "You cannot modify '%s'.".formatted(documentName));
        }
        if (versionId == null || versionId.isBlank()) {
            return toolResult("restoreVersion", "Provide the versionId to restore (see listVersions).");
        }
        RestoreVersionResponse response = blockWithAuth(versionService.restoreVersion(id, versionId.trim()));
        return toolResult("restoreVersion", "Restored '%s' from version %s (new current version %s)."
                .formatted(documentName, response.restoredFromVersionId(), response.newVersionId()));
    }

    @Tool(description = "Get a document's content: the extracted text for text/PDF/Office files, "
            + "plus a browser link to download the original file (the result says how long the "
            + "link stays valid and whether sign-in is needed).")
    public String downloadDocument(
            @ToolParam(description = "The name or reference of the document") String documentName) {
        DocumentDownload download = fetchDownload(documentName);
        if (download.error() != null) {
            return toolResult("downloadDocument", download.error());
        }
        Document doc = download.document();
        if (download.extractedText() != null) {
            return toolResult("downloadDocument", ("Content of '%s' (%s, %d bytes):\n\n%s\n\n"
                    + "Download the original file (%s): %s").formatted(
                    doc.getName(), contentTypeOf(doc), sizeOf(doc), download.extractedText(),
                    download.downloadHint(), download.downloadUrl()));
        }
        return toolResult("downloadDocument", ("'%s' is a %s file (%d bytes); its content is not text. "
                + "Download the original file (%s): %s").formatted(
                doc.getName(), contentTypeOf(doc), sizeOf(doc), download.downloadHint(), download.downloadUrl()));
    }

    /**
     * The structured form of {@link #downloadDocument}, shared by both front-ends: the chat
     * assistant formats it as text above; the MCP front-end ({@code McpDocumentResources})
     * renders it as a {@code resource_link} + text content blocks, so an MCP client can fetch
     * the original bytes over its own authenticated connection ({@code resources/read}).
     * Same gates as the tool: role policy, then per-document access policy.
     *
     * @return the document, its extracted text (null when not extractable) and browser download
     *         URL — or an {@code error} message, in which case every other field is null
     */
    public DocumentDownload fetchDownload(String documentName) {
        String roleDenial = denyIfNotAllowed("downloadDocument", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) {
            return DocumentDownload.failure(roleDenial);
        }
        UUID id = resolveDocumentToId(documentName);
        if (id == null || !canRead(id)) {
            return DocumentDownload.failure("No document named '%s' is visible to you.".formatted(documentName));
        }
        Document doc = blockWithAuth(documentRepository.findById(id));
        if (doc == null) {
            return DocumentDownload.failure("Document '%s' not found.".formatted(documentName));
        }
        if (doc.getType() == DocumentType.FOLDER) {
            return DocumentDownload.failure("'%s' is a folder, not a downloadable file.".formatted(documentName));
        }
        Resource resource = blockWithAuth(storageService.loadFile(doc.getStoragePath()));
        // Signed link: minted for this user, for this one document, only after the checks above
        // passed — so the URL is clickable straight from the conversation, expiring quickly.
        String token = downloadTokenService == null ? null : downloadTokenService.mint(id, userEmail);
        String hint = token != null
                ? "link valid about %d minutes, no sign-in needed"
                        .formatted(Math.max(1, downloadTokenService.ttlSeconds() / 60))
                : "browser, signed-in user";
        return new DocumentDownload(doc, extractText(resource), tokenizedDownloadUrl(id, token), hint, null);
    }

    /**
     * The raw bytes of a single document, for the MCP resource front-end
     * ({@code openfilz://documents/{id}} → {@code resources/read}). Enforces the same two gates
     * as every tool — the role policy for the kind of operation, the access policy for the
     * document — and answers for an inaccessible id exactly as for an id that does not exist,
     * so the resource URI space is not an existence oracle.
     *
     * @param maxBytes refuse (never truncate) content larger than this — the whole blob is
     *                 base64-inlined into a single JSON-RPC response
     */
    public DocumentContent fetchContent(UUID documentId, long maxBytes) {
        String roleDenial = denyIfNotAllowed("resources/read", ToolCapability.DOCUMENT_READ);
        if (roleDenial != null) {
            return DocumentContent.failure(roleDenial, false);
        }
        if (documentId == null || !canRead(documentId)) {
            return DocumentContent.notFound(documentId);
        }
        Document doc = blockWithAuth(documentRepository.findById(documentId));
        if (doc == null || doc.getType() == DocumentType.FOLDER
                || (doc.getActive() != null && !doc.getActive())) {
            return DocumentContent.notFound(documentId);
        }
        if (sizeOf(doc) > maxBytes) {
            return DocumentContent.failure(resourceTooLarge(doc, sizeOf(doc), maxBytes), false);
        }
        try {
            Resource resource = blockWithAuth(storageService.loadFile(doc.getStoragePath()));
            if (resource == null) {
                return DocumentContent.failure("Could not load '%s' from storage.".formatted(doc.getName()), false);
            }
            byte[] bytes = resource.getContentAsByteArray();
            if (bytes.length > maxBytes) {
                // The stored size can lie (null, stale after a replace) — re-check what was read
                return DocumentContent.failure(resourceTooLarge(doc, bytes.length, maxBytes), false);
            }
            return new DocumentContent(doc, bytes, null, false);
        } catch (Exception e) {
            log.error("[AI-TOOL] error reading document {} for resources/read", documentId, e);
            return DocumentContent.failure("Error reading document: " + e.getMessage(), false);
        }
    }

    private String resourceTooLarge(Document doc, long size, long maxBytes) {
        UUID id = doc.getId();
        String token = downloadTokenService == null ? null : downloadTokenService.mint(id, userEmail);
        return ("'%s' is %d bytes, above this server's %d-byte MCP resource limit. "
                + "Download it in a browser instead: %s").formatted(
                doc.getName(), size, maxBytes, tokenizedDownloadUrl(id, token));
    }

    /** The download URL, with the signed token appended when one was minted. */
    private String tokenizedDownloadUrl(UUID documentId, String token) {
        String url = downloadUrl(documentId);
        return token == null ? url : url + "?" + DownloadTokenService.TOKEN_PARAM + "=" + token;
    }

    /**
     * Everything a front-end needs to hand a document over, or an error message (exactly one of
     * {@code document}/{@code error} is set). {@code downloadHint} is a ready phrase describing
     * how the URL is usable ("browser, signed-in user" vs. an expiring signed link).
     */
    public record DocumentDownload(Document document, String extractedText, String downloadUrl,
                                   String downloadHint, String error) {
        static DocumentDownload failure(String error) {
            return new DocumentDownload(null, null, null, null, error);
        }
    }

    /** A document's raw bytes for the MCP resource front-end, or a refusal ({@code notFound} steers the error code). */
    public record DocumentContent(Document document, byte[] bytes, String error, boolean notFound) {
        static DocumentContent failure(String error, boolean notFound) {
            return new DocumentContent(null, null, error, notFound);
        }

        static DocumentContent notFound(UUID id) {
            return failure("No document with id '%s' is visible to you.".formatted(id), true);
        }
    }

    /** Maximum characters of a document's text handed to the model by readDocumentContent. */
    static final int MAX_CONTENT_CHARS = 8000;

    /**
     * The document's text from the full-text index (extracted at upload), truncated like
     * {@link #extractText}; null when full-text is off or the index holds nothing for it.
     */
    private String indexedText(Document doc) {
        if (indexService == null || doc.getId() == null) {
            return null;
        }
        try {
            String text = blockWithAuth(indexService.getContent(doc.getId()));
            if (text == null || text.isBlank()) {
                return null;
            }
            log.debug("[AI-TOOL] readDocumentContent served '{}' from the search index ({} chars)", doc.getName(), text.length());
            return truncateForModel(text);
        } catch (Exception e) {
            log.debug("[AI-TOOL] index lookup failed for {}, falling back to the file: {}", doc.getId(), e.getMessage());
            return null;
        }
    }

    private static String truncateForModel(String fullText) {
        return fullText.length() > MAX_CONTENT_CHARS
                ? fullText.substring(0, MAX_CONTENT_CHARS) + "\n\n[... content truncated, document is longer ...]"
                : fullText;
    }

    /** Extract text from a file with Tika, truncated for context, or null if none is extractable. */
    private String extractText(Resource resource) {
        if (resource == null) {
            return null;
        }
        try {
            var tikaDocuments = new TikaDocumentReader(resource).get();
            if (tikaDocuments == null || tikaDocuments.isEmpty()) {
                return null;
            }
            String fullText = tikaDocuments.stream().map(d -> d.getText()).collect(Collectors.joining("\n\n"));
            if (fullText == null || fullText.isBlank()) {
                return null;
            }
            return truncateForModel(fullText);
        } catch (Exception e) {
            log.debug("[AI-TOOL] no extractable text from {}: {}", resource, e.getMessage());
            return null;
        }
    }

    /** Absolute download URL for a document, against the configured public API base. */
    private String downloadUrl(UUID documentId) {
        String base = commonProperties.getApiPublicBaseUrl();
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS + "/" + documentId + "/download";
    }

    private static String contentTypeOf(Document doc) {
        return doc.getContentType() == null ? "unknown type" : doc.getContentType();
    }

    private static long sizeOf(Document doc) {
        return doc.getSize() == null ? 0L : doc.getSize();
    }

    /** Parse an optional FILE/FOLDER type argument, or null for both. */
    private static DocumentType parseType(String type) {
        if (type == null || type.isBlank() || "null".equalsIgnoreCase(type.trim())) {
            return null;
        }
        try {
            return DocumentType.valueOf(type.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Parse a JSON object argument into a map, tolerating null/blank/malformed input. */
    private static Map<String, Object> parseJsonObject(String json) {
        if (json == null || json.isBlank() || "null".equalsIgnoreCase(json.trim())) {
            return java.util.Collections.emptyMap();
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = JSON.readValue(json, Map.class);
            return map == null ? java.util.Collections.emptyMap() : map;
        } catch (Exception e) {
            return java.util.Collections.emptyMap();
        }
    }

    /**
     * Render PDF pages to images and analyze each with the vision model.
     * Uses Apache PDFBox to convert pages to PNG, then sends them as Media.
     */
    private String analyzePdfWithVision(Resource resource, String promptText, String docName) throws Exception {
        try (var inputStream = resource.getInputStream();
             var pdfDocument = org.apache.pdfbox.Loader.loadPDF(inputStream.readAllBytes())) {

            int totalPages = pdfDocument.getNumberOfPages();
            int pagesToAnalyze = Math.min(totalPages, MAX_PDF_PAGES_FOR_VISION);
            log.debug("[AI-TOOL] analyzePdfWithVision: '{}' has {} pages, analyzing {}", docName, totalPages, pagesToAnalyze);

            var renderer = new org.apache.pdfbox.rendering.PDFRenderer(pdfDocument);
            var pageResults = new ArrayList<String>();

            for (int i = 0; i < pagesToAnalyze; i++) {
                // Render page at 150 DPI (good balance between quality and size)
                var bufferedImage = renderer.renderImageWithDPI(i, 150);

                // Convert BufferedImage to PNG bytes
                var baos = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(bufferedImage, "png", baos);
                byte[] pngBytes = baos.toByteArray();

                // Wrap as a Spring Resource for Media
                var pageResource = new org.springframework.core.io.ByteArrayResource(pngBytes);
                var media = new Media(MimeType.valueOf("image/png"), pageResource);

                String pagePrompt = pagesToAnalyze > 1
                        ? "This is page %d of %d of a PDF document named '%s'. %s".formatted(i + 1, totalPages, docName, promptText)
                        : promptText;

                var userMessage = UserMessage.builder()
                        .text(pagePrompt)
                        .media(media)
                        .build();

                var response = chatModel.call(new Prompt(List.of(userMessage)));
                String pageText = response.getResult().getOutput().getText();

                if (pageText != null && !pageText.isBlank()) {
                    if (pagesToAnalyze > 1) {
                        pageResults.add("--- Page %d/%d ---\n%s".formatted(i + 1, totalPages, pageText));
                    } else {
                        pageResults.add(pageText);
                    }
                }
                log.debug("[AI-TOOL] analyzePdfWithVision: page {}/{} analyzed ({} chars)", i + 1, pagesToAnalyze, pageText != null ? pageText.length() : 0);
            }

            if (pageResults.isEmpty()) {
                return "Could not extract any content from the PDF.";
            }

            String combined = String.join("\n\n", pageResults);
            if (totalPages > pagesToAnalyze) {
                combined += "\n\n[... only the first %d of %d pages were analyzed ...]".formatted(pagesToAnalyze, totalPages);
            }
            return combined;
        }
    }
}
