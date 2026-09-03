package org.openfilz.dms.service.mcp;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.OrganizeAiTools;
import org.openfilz.dms.service.ai.OrganizeAiToolsRuntimeHints;
import org.openfilz.dms.service.ai.ReorganizationPlanService;
import org.openfilz.dms.service.ai.ToolCapability;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the document-reorganisation tools ({@link OrganizeAiTools}) with the shared tool layer,
 * for external MCP agents and the in-app assistant alike ({@link #exposeInChat()}).
 * <p>
 * {@code proposeReorganizationPlan} moves nothing, but it is classified as a write on purpose: it
 * only makes sense for a caller that will be allowed to apply the plan, and a {@code READ_ONLY}
 * MCP deployment should not accumulate proposals nobody can act on.
 */
@Component
@Lazy
@RequiredArgsConstructor
@ImportRuntimeHints(OrganizeAiToolsRuntimeHints.class)
public class OrganizeAiToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "planReorganization", ToolCapability.DOCUMENT_READ,
            "getReorganizationPlan", ToolCapability.DOCUMENT_READ,
            "proposeReorganizationPlan", ToolCapability.DOCUMENT_WRITE,
            "applyReorganizationPlan", ToolCapability.DOCUMENT_WRITE);

    /** Advertised in every MCP mode. */
    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    /** Withheld in {@code READ_ONLY} mode. */
    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating())
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

    private final ReorganizationPlanService planService;
    private final AiToolRolePolicy rolePolicy;

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        return new OrganizeAiTools(planService, rolePolicy).forUser(userEmail, authentication);
    }

    @Override
    public Map<String, ToolCapability> capabilities() {
        return CAPABILITIES;
    }

    /** The tools enforce the role gate themselves, so the chat may call them directly. */
    @Override
    public boolean exposeInChat() {
        return true;
    }
}
