package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.DocumentVersionService;
import org.openfilz.dms.service.ai.AiAccessPolicy;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.DocumentAiToolsFactory;
import org.openfilz.dms.service.ai.McpFileInspector;
import org.openfilz.dms.service.ai.McpFileTransferTools;
import org.openfilz.dms.service.ai.McpFileTransferToolsRuntimeHints;
import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the binary write tools for MCP agents. Not exposed to the chat
 * ({@link #exposeInChat()} stays false): base64 has no place in a model conversation.
 */
@Component
@Lazy
@ImportRuntimeHints(McpFileTransferToolsRuntimeHints.class)
@RequiredArgsConstructor
public class McpFileTransferToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "replaceDocumentContent", ToolCapability.DOCUMENT_WRITE,
            "uploadFile", ToolCapability.DOCUMENT_WRITE);

    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    private final DocumentService documentService;
    private final DocumentRepository documentRepository;
    private final DocumentVersionService versionService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final McpProperties properties;
    private final DocumentAiToolsFactory documentTools;
    private final McpFileInspector inspector;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        // No chat model: only the name resolution of the document tools is used here
        return new McpFileTransferTools(documentService, documentRepository, versionService, accessPolicy, rolePolicy,
                properties, documentTools.create(null, userEmail, authentication), inspector)
                .forUser(userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }
}
