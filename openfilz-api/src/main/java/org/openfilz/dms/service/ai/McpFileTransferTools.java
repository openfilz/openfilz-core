package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.exception.VersioningDisabledException;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.utils.ContentInfo;
import org.openfilz.dms.utils.PathFilePart;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import reactor.core.publisher.Mono;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.UUID;

/**
 * Binary writes for MCP agents: replace a document's content, or upload a new file, as base64.
 * Never bound into the chat: base64 has no place in a model conversation.
 * <p>
 * Both go through the regular {@link DocumentService} pipeline, from a temp file, so audit,
 * versioning, checksum, quota, thumbnails and indexing apply with nothing to do here.
 */
@Slf4j
public class McpFileTransferTools {

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final McpProperties properties;
    /** The one place names and ids resolve: exact name or id for a write, never a first match. */
    private final DocumentAiTools resolver;

    private String userEmail;
    private Authentication authentication;

    public McpFileTransferTools(DocumentService documentService, DocumentRepository documentRepository,
                                DocumentVersionService versionService, AiAccessPolicy accessPolicy,
                                AiToolRolePolicy rolePolicy, McpProperties properties, DocumentAiTools resolver) {
        this.documentService = documentService;
        this.documentRepository = documentRepository;
        this.versionService = versionService;
        this.accessPolicy = accessPolicy;
        this.rolePolicy = rolePolicy;
        this.properties = properties;
        this.resolver = resolver;
    }

    public McpFileTransferTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    @Tool(description = "Replace the content of an existing file with new bytes, as a new version. Use this to save "
            + "a spreadsheet, a PDF or any other binary file back; writeFile only creates text files.")
    public String replaceDocumentContent(
            @ToolParam(description = "Exact name or id of the file to replace") String document,
            @ToolParam(description = "The new content, base64-encoded") String contentBase64,
            @ToolParam(required = false, description = "Optional: the version your work is based on (from listVersions, "
                    + "or from the previous answer of this tool). If the file has changed since, nothing is written "
                    + "and the answer names the current version.") String expectedVersionId) {
        String denial = denyIfNotAllowed();
        if (denial != null) {
            return denial;
        }
        String tooLarge = refuseIfTooLarge(contentBase64);
        if (tooLarge != null) {
            return tooLarge;
        }
        try {
            DocumentAiTools.MutationTarget target = resolver.resolveForMutation(document, "replace");
            if (target.error() != null) {
                return target.error();
            }
            Document current = target.document();
            if (current.getType() == DocumentType.FOLDER) {
                return "'%s' is a folder: only a file has content to replace.".formatted(current.getName());
            }
            if (!allowed(accessPolicy.canModify(current.getId(), userEmail))) {
                return "You cannot modify '%s'.".formatted(current.getName());
            }
            if (expectedVersionId != null && !expectedVersionId.isBlank()) {
                String currentVersion = versionOf(current);
                if (!currentVersion.equals(expectedVersionId.trim())) {
                    return ("'%s' has changed since version %s: its current version is %s. Nothing was written. "
                            + "Read the current content and start again from it.")
                            .formatted(current.getName(), expectedVersionId.trim(), currentVersion);
                }
            }
            return withDecodedFile(contentBase64, (file, size) -> {
                blockWithAuth(documentService.replaceDocumentContent(current.getId(),
                        new PathFilePart("file", current.getName(), file), new ContentInfo(size, null)));
                // Read back rather than trust the returned entity: the stored date is what a later call compares with
                Document saved = blockWithAuth(documentRepository.findById(current.getId()));
                log.info("[AI-TOOL] replaceDocumentContent: '{}' ({} bytes)", current.getName(), size);
                return "Replaced '%s' (id %s): %d bytes, now version %s."
                        .formatted(current.getName(), current.getId(), size, versionOf(saved != null ? saved : current));
            });
        } catch (Exception e) {
            log.warn("[AI-TOOL] replaceDocumentContent failed: {}", e.getMessage());
            return "Error replacing the document: " + e.getMessage();
        }
    }

    @Tool(description = "Upload a new file with binary content, such as a spreadsheet or a PDF. Use writeFile for plain "
            + "text, and replaceDocumentContent to change a file that already exists.")
    public String uploadFile(
            @ToolParam(description = "The name of the new file, with its extension") String fileName,
            @ToolParam(description = "The content, base64-encoded") String contentBase64,
            @ToolParam(required = false, description = "Optional: name or id of the folder to put it in. Default: the root") String folderName) {
        String denial = denyIfNotAllowed();
        if (denial != null) {
            return denial;
        }
        if (fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
            return "Give a file name, with no path in it.";
        }
        String tooLarge = refuseIfTooLarge(contentBase64);
        if (tooLarge != null) {
            return tooLarge;
        }
        try {
            UUID parentId = null;
            if (folderName != null && !folderName.isBlank() && !"root".equalsIgnoreCase(folderName.trim())) {
                DocumentAiTools.ReadTarget folder = resolver.resolveFolderForRead(folderName,
                        "No folder named '%s' exists. Create it first (createFolder), then call again.".formatted(folderName));
                if (folder.error() != null) {
                    return folder.error();
                }
                parentId = folder.document().getId();
            }
            boolean mayCreate = parentId == null
                    ? allowed(accessPolicy.canCreateAtRoot(userEmail))
                    : allowed(accessPolicy.canModify(parentId, userEmail));
            if (!mayCreate) {
                return "You don't have permission to create files in %s."
                        .formatted(parentId == null ? "the root folder" : "folder '%s'".formatted(folderName));
            }
            UUID parent = parentId;
            String name = fileName.trim();
            return withDecodedFile(contentBase64, (file, size) -> {
                var response = blockWithAuth(documentService.uploadDocument(
                        new PathFilePart("file", name, file), size, parent, null, Boolean.FALSE));
                if (response == null || response.id() == null) {
                    return "Failed to save the file%s.".formatted(
                            response != null && response.errorMessage() != null ? ": " + response.errorMessage() : "");
                }
                log.info("[AI-TOOL] uploadFile: '{}' ({} bytes) in folder {}", name, size, parent);
                Document saved = blockWithAuth(documentRepository.findById(response.id()));
                return "Uploaded '%s' (id %s): %d bytes, version %s."
                        .formatted(name, response.id(), size, saved != null ? versionOf(saved) : "unknown");
            });
        } catch (Exception e) {
            log.warn("[AI-TOOL] uploadFile failed: {}", e.getMessage());
            return "Error uploading the file: " + e.getMessage();
        }
    }

    /** The document's version: the latest stored one, or its last update date where versioning is off. */
    private String versionOf(Document document) {
        try {
            DocumentVersionInfo latest = blockWithAuth(versionService.listVersions(document.getId()).next());
            if (latest != null) {
                return latest.versionId();
            }
        } catch (VersioningDisabledException e) {
            log.trace("Versioning is off: the update date stands for the version");
        }
        OffsetDateTime updated = document.getUpdatedAt();
        return String.valueOf(updated != null ? updated.toInstant() : null);
    }

    @FunctionalInterface
    private interface FileAction {
        String apply(Path file, long size) throws Exception;
    }

    private String withDecodedFile(String contentBase64, FileAction action) throws Exception {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(contentBase64 == null ? "" : contentBase64.strip());
        } catch (IllegalArgumentException e) {
            return "The content is not valid base64: " + e.getMessage();
        }
        if (bytes.length > properties.getMaxResourceSizeBytes()) {
            return tooLargeMessage(bytes.length);
        }
        Path file = Files.createTempFile("mcp-transfer-", ".bin");
        try {
            Files.write(file, bytes);
            return action.apply(file, bytes.length);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    /** Checked on the encoded length, before any of it is decoded. */
    private String refuseIfTooLarge(String contentBase64) {
        if (contentBase64 == null) {
            return null;
        }
        String encoded = contentBase64.strip();
        long padding = encoded.endsWith("==") ? 2 : encoded.endsWith("=") ? 1 : 0;
        long decoded = encoded.length() / 4L * 3L - padding;
        return decoded > properties.getMaxResourceSizeBytes() ? tooLargeMessage(decoded) : null;
    }

    private String tooLargeMessage(long size) {
        return "The content is about %d bytes, above this server's %d-byte limit for a file sent through MCP. "
                .formatted(size, properties.getMaxResourceSizeBytes())
                + "Upload it in the OpenFilz application instead.";
    }

    private String denyIfNotAllowed() {
        if (rolePolicy == null || rolePolicy.isAllowed(authentication, ToolCapability.DOCUMENT_WRITE)) {
            return null;
        }
        return "Not permitted: your OpenFilz role does not allow this operation (DOCUMENT_WRITE). "
                + "Ask an administrator for the required role.";
    }

    private boolean allowed(Mono<Boolean> decision) {
        return Boolean.TRUE.equals(decision.block());
    }

    /** Tool threads have no ambient Reactor context: the caller's identity goes in with each call. */
    private <T> T blockWithAuth(Mono<T> mono) {
        return (authentication != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(authentication))
                : mono).block();
    }
}
