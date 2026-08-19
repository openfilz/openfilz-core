package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class DocumentAiTools {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AiDocumentQueryService queryService;
    private final ChatModel chatModel;

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

    /** Clear the registry before each new user message. */
    public void clearRegistry() {
        documentRegistry.clear();
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

    /**
     * Post-process AI response text:
     * 1. Replace known document names with [[doc:...]] markers
     * 2. Strip any leftover UUID references the LLM included
     */
    public String enrichWithDocLinks(String text) {
        if (text == null || documentRegistry.isEmpty()) return text;

        // Sort by name length descending to replace longest matches first
        var sortedRefs = documentRegistry.values().stream()
                .sorted((a, b) -> b.name().length() - a.name().length())
                .toList();

        // First strip all UUID references from the LLM text
        String result = UUID_LABEL_PATTERN.matcher(text).replaceAll("");
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
            @ToolParam(description = "Folder name to list contents of, or null for root folder, or 'all' to search across all folders") String folder,
            @ToolParam(description = "Filter by name (partial match, case-insensitive). Use this to search for files.") String nameLike,
            @ToolParam(description = "Filter by type: FILE, FOLDER, or null for both") String type,
            @ToolParam(description = "Sort by field: name, createdAt, updatedAt, size. Default: updatedAt") String sortBy,
            @ToolParam(description = "Sort order: ASC or DESC. Default: DESC") String sortOrder,
            @ToolParam(description = "Max results to return (1-50). Default: 10") Integer pageSize,
            @ToolParam(description = "Set to true to only return the count, not the documents") Boolean countOnly
    ) {
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
                long count = queryService.count(request);
                return toolResult("queryDocuments", "Found %d document(s).".formatted(count));
            }

            // Query
            var results = queryService.query(request);
            if (results == null || results.isEmpty()) {
                return toolResult("queryDocuments", "No documents found.");
            }

            // Register all results for doc-link enrichment
            results.forEach(r -> register(r.id(), r.parentId(), r.type().name(), r.name()));

            // Format results
            String formatted = results.stream()
                    .map(r -> "- [%s] %s (%s, %s)".formatted(
                            r.type().name(),
                            r.name(),
                            r.contentType() != null ? r.contentType() : "unknown type",
                            r.createdAt() != null ? r.createdAt().toLocalDate().toString() : "unknown date"))
                    .collect(Collectors.joining("\n"));

            return toolResult("queryDocuments", "Found %d result(s):\n%s".formatted(results.size(), formatted));
        } catch (Exception e) {
            log.error("Error querying documents", e);
            return "Error querying documents: " + e.getMessage();
        }
    }

    @Tool(description = "Write text content to a new file in the document library. Use this when the user asks to save, write, or export text to a file.")
    public String writeFile(
            @ToolParam(description = "The filename to create (e.g., 'summary.md', 'report.txt')") String fileName,
            @ToolParam(description = "The text content to write into the file") String content,
            @ToolParam(description = "The folder name to save in, or null for root") String folderName
    ) {
        log.debug("[AI-TOOL] writeFile called with: file='{}', folder='{}', content={}chars", fileName, folderName, content != null ? content.length() : 0);
        try {
            UUID parentId = resolveToId(folderName);

            // Create a temporary file with the content
            java.nio.file.Path tempFile = java.nio.file.Files.createTempFile("ai-write-", "-" + fileName);
            java.nio.file.Files.writeString(tempFile, content != null ? content : "");
            long fileSize = java.nio.file.Files.size(tempFile);

            // Create a FilePart from the temp file
            var resource = new org.springframework.core.io.FileSystemResource(tempFile.toFile());

            // Determine content type from extension
            String contentType = "text/plain";
            if (fileName.endsWith(".md")) contentType = "text/markdown";
            else if (fileName.endsWith(".html")) contentType = "text/html";
            else if (fileName.endsWith(".json")) contentType = "application/json";
            else if (fileName.endsWith(".xml")) contentType = "application/xml";
            else if (fileName.endsWith(".csv")) contentType = "text/csv";

            // Save to storage
            String storagePath = storageService.saveFile(new org.openfilz.dms.utils.PathFilePart("file", fileName, tempFile)).block();

            // Create document record in DB
            var doc = Document.builder()
                    .name(fileName)
                    .type(org.openfilz.dms.enums.DocumentType.FILE)
                    .contentType(contentType)
                    .size(fileSize)
                    .storagePath(storagePath)
                    .parentId(parentId)
                    .active(true)
                    .build();

            var saved = documentRepository.save(doc).block();

            // Clean up temp file
            java.nio.file.Files.deleteIfExists(tempFile);

            if (saved != null) {
                register(saved);
                log.info("[AI-TOOL] writeFile: created '{}' ({} bytes) in folder {}", fileName, fileSize, parentId);
                return toolResult("writeFile", "File '%s' created successfully.".formatted(fileName));
            }
            return toolResult("writeFile", "Failed to save the file.");
        } catch (Exception e) {
            log.error("Error writing file", e);
            return "Error writing file: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new folder. Returns the new folder's ID.")
    public String createFolder(
            @ToolParam(description = "Name of the new folder") String name,
            @ToolParam(description = "UUID of the parent folder. Use null for root.") String parentFolderId
    ) {
        log.debug("[AI-TOOL] createFolder called with: name='{}', parent='{}'", name, parentFolderId);
        try {
            UUID parentId = parseUuid(parentFolderId);
            var request = new CreateFolderRequest(name, parentId);

            var result = documentService.createFolder(request).block();
            return "Folder '%s' created successfully with ID: %s".formatted(name, result.id());
        } catch (Exception e) {
            log.error("Error creating folder", e);
            return "Error creating folder: " + e.getMessage();
        }
    }

    @Tool(description = "Move files or folders to a different folder. Accepts document/folder names or IDs.")
    public String moveDocuments(
            @ToolParam(description = "Comma-separated list of document or folder names to move") String documentNames,
            @ToolParam(description = "Name of the target folder, or null for root.") String targetFolder
    ) {
        log.debug("[AI-TOOL] moveDocuments called with: items='{}', target='{}'", documentNames, targetFolder);
        try {
            // Resolve target folder
            UUID targetId = resolveToId(targetFolder);

            // Resolve each item to move
            List<UUID> fileIds = new ArrayList<>();
            List<UUID> folderIds = new ArrayList<>();

            for (String name : documentNames.split(",")) {
                String trimmed = name.trim();
                UUID id = parseUuid(trimmed);
                Document doc = null;

                if (id != null) {
                    doc = documentRepository.findById(id).block();
                } else {
                    // Resolve by name from registry
                    var ref = documentRegistry.get(trimmed);
                    if (ref != null) {
                        id = ref.id();
                        doc = documentRepository.findById(id).block();
                    } else {
                        // Search by name
                        var found = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(trimmed)
                                .collectList().block();
                        if (found != null && !found.isEmpty()) {
                            doc = found.getFirst();
                            id = doc.getId();
                        }
                    }
                }

                if (doc != null && id != null) {
                    if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                        folderIds.add(id);
                    } else {
                        fileIds.add(id);
                    }
                }
            }

            int moved = 0;
            if (!fileIds.isEmpty()) {
                documentService.moveFiles(new MoveRequest(fileIds, targetId, false)).block();
                moved += fileIds.size();
            }
            if (!folderIds.isEmpty()) {
                documentService.moveFolders(new MoveRequest(folderIds, targetId, false)).block();
                moved += folderIds.size();
            }

            if (moved == 0) return "No matching documents found to move.";
            return "Successfully moved %d item(s).".formatted(moved);
        } catch (Exception e) {
            log.error("Error moving documents", e);
            return "Error moving documents: " + e.getMessage();
        }
    }

    /** Resolve a name or UUID string to a UUID, searching the registry and DB if needed. */
    private UUID resolveToId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank() || "null".equalsIgnoreCase(nameOrId)
                || "root".equalsIgnoreCase(nameOrId) || "My Folder".equalsIgnoreCase(nameOrId)) {
            return null; // root folder
        }
        UUID id = parseUuid(nameOrId);
        if (id != null) return id;
        var ref = documentRegistry.get(nameOrId);
        if (ref != null) return ref.id();
        // Search DB
        var found = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(nameOrId)
                .filter(d -> d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER)
                .collectList().block();
        if (found != null && !found.isEmpty()) {
            register(found.getFirst());
            return found.getFirst().getId();
        }
        return null;
    }

    @Tool(description = "Rename a file or folder.")
    public String renameDocument(
            @ToolParam(description = "Name or reference of the document or folder to rename") String documentName,
            @ToolParam(description = "The new name") String newName
    ) {
        log.debug("[AI-TOOL] renameDocument called with: '{}' -> '{}'", documentName, newName);
        try {
            UUID id = resolveToId(documentName);
            if (id == null) {
                // Also try searching files
                var found = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(documentName)
                        .collectList().block();
                if (found != null && !found.isEmpty()) {
                    id = found.getFirst().getId();
                }
            }
            var renameRequest = new RenameRequest(newName);

            Document doc = id != null ? documentRepository.findById(id).block() : null;
            if (doc == null) {
                return "Document '%s' not found.".formatted(documentName);
            }

            if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                documentService.renameFolder(id, renameRequest).block();
            } else {
                documentService.renameFile(id, renameRequest).block();
            }

            return "Successfully renamed to '%s'.".formatted(newName);
        } catch (Exception e) {
            log.error("Error renaming document", e);
            return "Error renaming: " + e.getMessage();
        }
    }

    @Tool(description = "Read and extract the text content of a document file. You can optionally specify a folder name to search in.")
    public String readDocumentContent(
            @ToolParam(description = "The name (or part of the name) of the document to read") String documentName,
            @ToolParam(description = "Optional: the folder name where the document is located. Helps find the right file when the name is ambiguous.") String folderName
    ) {
        log.debug("[AI-TOOL] readDocumentContent called with: document='{}', folder='{}'", documentName, folderName);
        try {
            Document doc = null;

            // If a folder is specified, list its contents and find the best match
            if (folderName != null && !folderName.isBlank() && !"null".equalsIgnoreCase(folderName)) {
                UUID folderId = resolveToId(folderName);
                if (folderId == null) {
                    // Try searching for the folder
                    var folders = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(folderName)
                            .filter(d -> d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER)
                            .collectList().block();
                    if (folders != null && !folders.isEmpty()) {
                        folderId = folders.getFirst().getId();
                    }
                }
                if (folderId != null) {
                    log.debug("[AI-TOOL] readDocumentContent: searching in folder {} for '{}'", folderId, documentName);
                    // Use queryService to search within folder by name
                    var request = new ListFolderRequest(folderId, DocumentType.FILE, null, null, documentName,
                            null, null, null, null, null, null, null, null, null, true,
                            new PageCriteria("name", SortOrder.ASC, 1, 10), false);
                    var results = queryService.query(request);
                    if (results != null && !results.isEmpty()) {
                        doc = documentRepository.findById(results.getFirst().id()).block();
                        log.debug("[AI-TOOL] readDocumentContent: found '{}' in folder", doc != null ? doc.getName() : "null");
                    } else {
                        // No match — list all files in folder for the LLM
                        var allInFolder = new ListFolderRequest(folderId, DocumentType.FILE, null, null, null,
                                null, null, null, null, null, null, null, null, null, true,
                                new PageCriteria("name", SortOrder.ASC, 1, 20), false);
                        var allFiles = queryService.query(allInFolder);
                        String fileList = allFiles != null ? allFiles.stream()
                                .map(f -> "- " + f.name()).collect(Collectors.joining("\n")) : "(empty)";
                        return toolResult("readDocumentContent",
                                "No file matching '%s' found in folder '%s'. Files in this folder:\n%s".formatted(documentName, folderName, fileList));
                    }
                }
            }

            // Fallback: resolve by name globally
            if (doc == null) {
                UUID id = resolveAnyToId(documentName);
                if (id == null) return toolResult("readDocumentContent", "Document '%s' not found.".formatted(documentName));
                doc = documentRepository.findById(id).block();
            }

            if (doc == null) return toolResult("readDocumentContent", "Document not found.");
            if (doc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                return toolResult("readDocumentContent", "'%s' is a folder. Use listFolder to see its contents.".formatted(documentName));
            }
            if (doc.getActive() != null && !doc.getActive()) {
                return toolResult("readDocumentContent", "Document '%s' has been deleted.".formatted(doc.getName()));
            }

            register(doc);

            // Load the file from storage and extract text with Tika
            Resource resource = storageService.loadFile(doc.getStoragePath()).block();
            if (resource == null) return "Could not load the file from storage.";

            var tikaReader = new TikaDocumentReader(resource);
            var tikaDocuments = tikaReader.get();

            if (tikaDocuments == null || tikaDocuments.isEmpty()) {
                return "Could not extract text from this file. It may be a binary or image file.";
            }

            // Concatenate all text content (truncate to avoid huge context)
            String fullText = tikaDocuments.stream()
                    .map(d -> d.getText())
                    .collect(Collectors.joining("\n\n"));

            // Limit to ~8000 characters to avoid overwhelming the LLM context
            if (fullText.length() > 8000) {
                fullText = fullText.substring(0, 8000) + "\n\n[... content truncated, document is longer ...]";
            }

            return "Content of '%s':\n\n%s".formatted(doc.getName(), fullText);
        } catch (Exception e) {
            log.error("Error reading document content", e);
            return "Error reading document: " + e.getMessage();
        }
    }

    /** Resolve a name or UUID to any document (file or folder). */
    private UUID resolveAnyToId(String nameOrId) {
        if (nameOrId == null || nameOrId.isBlank()) return null;
        UUID id = parseUuid(nameOrId);
        if (id != null) return id;
        var ref = documentRegistry.get(nameOrId);
        if (ref != null) return ref.id();
        var found = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(nameOrId)
                .collectList().block();
        if (found != null && !found.isEmpty()) {
            register(found.getFirst());
            return found.getFirst().getId();
        }
        return null;
    }

    @Tool(description = "Get the full path (ancestors) of a document from root to its parent folder.")
    public String getDocumentPath(
            @ToolParam(description = "UUID of the document") String documentId
    ) {
        try {
            var ancestors = documentService.getDocumentAncestors(parseUuid(documentId))
                    .collectList()
                    .block();

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
            @ToolParam(description = "Optional: the folder name where the image is located") String folderName,
            @ToolParam(description = "The task: 'describe' for description/caption, 'ocr' to extract text, or 'answer' to answer a specific question") String task,
            @ToolParam(description = "Optional: the specific question to answer about the image (used when task='answer')") String question
    ) {
        log.debug("[AI-TOOL] describeImage called: image='{}', folder='{}', task='{}', question='{}'",
                imageName, folderName, task, question);
        try {
            Document doc = null;

            // If a folder is specified, search within it first
            if (folderName != null && !folderName.isBlank() && !"null".equalsIgnoreCase(folderName)) {
                UUID folderId = resolveToId(folderName);
                if (folderId == null) {
                    var folders = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(folderName)
                            .filter(d -> d.getType() == DocumentType.FOLDER)
                            .collectList().block();
                    if (folders != null && !folders.isEmpty()) {
                        folderId = folders.getFirst().getId();
                    }
                }
                if (folderId != null) {
                    var request = new ListFolderRequest(folderId, DocumentType.FILE, null, null, imageName,
                            null, null, null, null, null, null, null, null, null, true,
                            new PageCriteria("name", SortOrder.ASC, 1, 10), false);
                    var results = queryService.query(request);
                    if (results != null && !results.isEmpty()) {
                        doc = documentRepository.findById(results.getFirst().id()).block();
                    }
                }
            }

            // Fallback: resolve by name globally
            if (doc == null) {
                UUID id = resolveAnyToId(imageName);
                if (id == null) return toolResult("describeImage", "Image '%s' not found.".formatted(imageName));
                doc = documentRepository.findById(id).block();
            }

            if (doc == null) return toolResult("describeImage", "Image not found.");
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
            Resource resource = storageService.loadFile(doc.getStoragePath()).block();
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
