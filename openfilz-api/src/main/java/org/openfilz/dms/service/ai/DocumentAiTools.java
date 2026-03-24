package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI tool functions that the LLM can invoke via function calling.
 * These wrap existing OpenFilz services and expose them as callable tools
 * for the AI assistant to perform actions on behalf of the user.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class DocumentAiTools {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;

    @Tool(description = "List the contents of a folder. Returns file and folder names with their IDs and types. Pass null for folderId to list root folder contents.")
    public String listFolder(
            @ToolParam(description = "The UUID of the folder to list. Use null for the root folder.") String folderId
    ) {
        try {
            UUID parentId = folderId != null && !folderId.isBlank() ? UUID.fromString(folderId) : null;
            var elements = documentService.listFolderInfo(parentId, false, false)
                    .collectList()
                    .block();

            if (elements == null || elements.isEmpty()) {
                return "The folder is empty.";
            }

            return elements.stream()
                    .map(e -> "- [%s] %s (id: %s)".formatted(
                            e.type().name(),
                            e.name(),
                            e.id()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("Error listing folder", e);
            return "Error listing folder: " + e.getMessage();
        }
    }

    @Tool(description = "Search for documents by name. Returns matching files and folders with their IDs.")
    public String searchByName(
            @ToolParam(description = "The search term to look for in document names") String query
    ) {
        try {
            var results = documentRepository.findByNameContainingIgnoreCaseAndActiveTrue(query)
                    .collectList()
                    .block();

            if (results == null || results.isEmpty()) {
                return "No documents found matching '" + query + "'.";
            }

            return results.stream()
                    .map(d -> "- [%s] %s (id: %s)".formatted(d.getType().name(), d.getName(), d.getId()))
                    .collect(Collectors.joining("\n"));
        } catch (Exception e) {
            log.error("Error searching documents", e);
            return "Error searching: " + e.getMessage();
        }
    }

    @Tool(description = "Get detailed information about a specific document or folder by its ID.")
    public String getDocumentInfo(
            @ToolParam(description = "The UUID of the document or folder") String documentId
    ) {
        try {
            var info = documentService.getDocumentInfo(UUID.fromString(documentId), true)
                    .block();

            if (info == null) {
                return "Document not found.";
            }

            return """
                    Name: %s
                    Type: %s
                    Content Type: %s
                    Size: %s bytes
                    Parent Folder ID: %s
                    """.formatted(
                    info.name(),
                    info.type(),
                    info.contentType() != null ? info.contentType() : "N/A",
                    info.size() != null ? info.size() : "N/A",
                    info.parentId() != null ? info.parentId() : "root"
            );
        } catch (Exception e) {
            log.error("Error getting document info", e);
            return "Error getting document info: " + e.getMessage();
        }
    }

    @Tool(description = "Create a new folder. Returns the new folder's ID.")
    public String createFolder(
            @ToolParam(description = "Name of the new folder") String name,
            @ToolParam(description = "UUID of the parent folder. Use null for root.") String parentFolderId
    ) {
        try {
            UUID parentId = parentFolderId != null && !parentFolderId.isBlank()
                    ? UUID.fromString(parentFolderId) : null;
            var request = new CreateFolderRequest(name, parentId);

            var result = documentService.createFolder(request).block();
            return "Folder '%s' created successfully with ID: %s".formatted(name, result.id());
        } catch (Exception e) {
            log.error("Error creating folder", e);
            return "Error creating folder: " + e.getMessage();
        }
    }

    @Tool(description = "Move files or folders to a different folder.")
    public String moveDocuments(
            @ToolParam(description = "Comma-separated list of document/folder UUIDs to move") String documentIds,
            @ToolParam(description = "UUID of the target folder. Use null for root.") String targetFolderId
    ) {
        try {
            List<UUID> ids = List.of(documentIds.split(",")).stream()
                    .map(String::trim)
                    .map(UUID::fromString)
                    .toList();

            UUID targetId = targetFolderId != null && !targetFolderId.isBlank()
                    ? UUID.fromString(targetFolderId) : null;

            var moveRequest = new MoveRequest(ids, targetId, false);

            // Determine if these are files or folders
            Document firstDoc = documentRepository.findById(ids.getFirst()).block();
            if (firstDoc != null && firstDoc.getType() == org.openfilz.dms.enums.DocumentType.FOLDER) {
                documentService.moveFolders(moveRequest).block();
            } else {
                documentService.moveFiles(moveRequest).block();
            }

            return "Successfully moved %d item(s) to the target folder.".formatted(ids.size());
        } catch (Exception e) {
            log.error("Error moving documents", e);
            return "Error moving documents: " + e.getMessage();
        }
    }

    @Tool(description = "Rename a file or folder.")
    public String renameDocument(
            @ToolParam(description = "UUID of the document or folder to rename") String documentId,
            @ToolParam(description = "The new name") String newName
    ) {
        try {
            UUID id = UUID.fromString(documentId);
            var renameRequest = new RenameRequest(newName);

            Document doc = documentRepository.findById(id).block();
            if (doc == null) {
                return "Document not found.";
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

    @Tool(description = "Count the number of items in a folder.")
    public String countFolderElements(
            @ToolParam(description = "UUID of the folder. Use null for root.") String folderId
    ) {
        try {
            UUID parentId = folderId != null && !folderId.isBlank() ? UUID.fromString(folderId) : null;
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
            var ancestors = documentService.getDocumentAncestors(UUID.fromString(documentId))
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
