package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.StorageService;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

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

    @Tool(description = "List the contents of a folder. Pass the folder name or null for the root folder.")
    public String listFolder(
            @ToolParam(description = "The folder name to list contents of, or null for the root folder.") String folderId
    ) {
        log.debug("[AI-TOOL] listFolder called with: '{}'", folderId);
        try {
            UUID parentId = parseUuid(folderId);
            // If not a UUID, try to resolve by folder name
            if (parentId == null && folderId != null && !folderId.isBlank() && !"null".equalsIgnoreCase(folderId)) {
                var match = documentRegistry.get(folderId);
                if (match != null && "FOLDER".equals(match.type())) {
                    parentId = match.id();
                } else {
                    // Search for the folder by name
                    var found = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(folderId)
                            .filter(d -> d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER)
                            .collectList().block();
                    if (found != null && !found.isEmpty()) {
                        var folder = found.getFirst();
                        parentId = folder.getId();
                        register(folder);
                    } else {
                        return "No folder named '%s' found.".formatted(folderId);
                    }
                }
            }
            final UUID resolvedParentId = parentId;
            var elements = documentService.listFolderInfo(resolvedParentId, false, false)
                    .collectList()
                    .block();

            if (elements == null || elements.isEmpty()) {
                return "The folder is empty.";
            }

            elements.forEach(e -> {
                log.debug("listFolder result: type={}, name={}, id={}", e.type(), e.name(), e.id());
                register(e.id(), resolvedParentId, e.type().name(), e.name());
            });

            String result = elements.stream()
                    .map(e -> "- %s %s".formatted(
                            e.type() == org.openfilz.dms.enums.DocumentType.FOLDER ? "[FOLDER]" : "[FILE]",
                            e.name()))
                    .collect(Collectors.joining("\n"));
            return toolResult("listFolder", result);
        } catch (Exception e) {
            log.error("Error listing folder", e);
            return "Error listing folder: " + e.getMessage();
        }
    }

    @Tool(description = "Search for documents and folders by name. Returns matching items.")
    public String searchByName(
            @ToolParam(description = "The search term to look for in document/folder names") String query
    ) {
        log.debug("[AI-TOOL] searchByName called with: '{}'", query);
        try {
            var results = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(query)
                    .collectList()
                    .block();

            if (results == null || results.isEmpty()) {
                return "No documents found matching '" + query + "'.";
            }

            results.forEach(this::register);

            return results.stream()
                    .map(d -> "- %s %s".formatted(
                            d.getType() == org.openfilz.dms.enums.DocumentType.FOLDER ? "[FOLDER]" : "[FILE]",
                            d.getName()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("Error searching documents", e);
            return "Error searching: " + e.getMessage();
        }
    }

    @Tool(description = "Get detailed information about a document or folder by name. Use searchByName first if needed.")
    public String getDocumentInfo(
            @ToolParam(description = "The name or internal reference of the document or folder") String documentId
    ) {
        log.debug("[AI-TOOL] getDocumentInfo called with: '{}'", documentId);
        try {
            UUID id = parseUuid(documentId);
            if (id == null) {
                // Try to find by name in the registry
                var ref = documentRegistry.get(documentId);
                if (ref != null) id = ref.id();
            }
            if (id == null) return "Document not found. Use searchByName to find it first.";

            var info = documentService.getDocumentInfo(id, true).block();
            if (info == null) return "Document not found.";

            register(id, info.parentId(), info.type().name(), info.name());

            return """
                    Name: %s
                    Type: %s
                    Content Type: %s
                    Size: %s bytes
                    """.formatted(
                    info.name(),
                    info.type(),
                    info.contentType() != null ? info.contentType() : "N/A",
                    info.size() != null ? info.size() : "N/A"
            );
        } catch (Exception e) {
            log.error("Error getting document info", e);
            return "Error getting document info: " + e.getMessage();
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
                    var elements = documentService.listFolderInfo(folderId, false, false)
                            .collectList().block();
                    if (elements != null) {
                        // Find the best match by name
                        var match = elements.stream()
                                .filter(e -> e.type() == org.openfilz.dms.enums.DocumentType.FILE)
                                .filter(e -> e.name().toLowerCase().contains(documentName.toLowerCase()))
                                .findFirst();
                        if (match.isPresent()) {
                            doc = documentRepository.findById(match.get().id()).block();
                            log.debug("[AI-TOOL] readDocumentContent: found '{}' in folder", doc != null ? doc.getName() : "null");
                        } else {
                            // No match by search term — list all files for the LLM
                            String fileList = elements.stream()
                                    .filter(e -> e.type() == org.openfilz.dms.enums.DocumentType.FILE)
                                    .map(e -> "- " + e.name())
                                    .collect(Collectors.joining("\n"));
                            return toolResult("readDocumentContent",
                                    "No file matching '%s' found in folder '%s'. Files in this folder:\n%s".formatted(documentName, folderName, fileList));
                        }
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

    @Tool(description = "Count the number of items in a folder.")
    public String countFolderElements(
            @ToolParam(description = "UUID of the folder. Use null for root.") String folderId
    ) {
        try {
            UUID parentId = parseUuid(folderId);
            Long count = documentService.countFolderElements(parentId).block();
            return "The folder contains %d item(s).".formatted(count != null ? count : 0);
        } catch (Exception e) {
            log.error("Error counting folder elements", e);
            return "Error counting: " + e.getMessage();
        }
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
}
