package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.FilingAiTools;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.filing.AutoFileService;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Registers the smart-filing tool ({@link FilingAiTools#fileDocuments}) for MCP agents and the chat. */
@Component
@Lazy
@RequiredArgsConstructor
public class FilingAiToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "fileDocuments", ToolCapability.DOCUMENT_WRITE);

    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    private final AutoFileService autoFileService;
    private final AiToolRolePolicy rolePolicy;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        return new FilingAiTools(autoFileService, rolePolicy).forUser(userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /** The tool enforces the role gate itself, so the chat may call it directly. */
    @Override
    public boolean exposeInChat() {
        return true;
    }
}
