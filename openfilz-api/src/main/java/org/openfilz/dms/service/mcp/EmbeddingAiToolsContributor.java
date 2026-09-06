package org.openfilz.dms.service.mcp;

import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.EmbeddingAiTools;
import org.openfilz.dms.service.ai.EmbeddingAiToolsRuntimeHints;
import org.openfilz.dms.service.ai.ReorganizationPlanService;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.impl.EmbeddingBackfillService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Registers the embedding backfill tools ({@link EmbeddingAiTools}) for MCP agents and the
 * chat: start a backfill, follow it. The backfill service is resolved per call through a
 * provider — a concrete {@code @Lazy} class, and a {@code @Lazy} injection point would be a
 * CGLIB proxy with no reflection metadata in the native image.
 */
@Component
@Lazy
@ImportRuntimeHints(EmbeddingAiToolsRuntimeHints.class)
public class EmbeddingAiToolsContributor implements McpToolContributor {

    public static final Map<String, ToolCapability> CAPABILITIES = Map.of(
            "backfillEmbeddings", ToolCapability.DOCUMENT_WRITE,
            "getEmbeddingBackfillStatus", ToolCapability.DOCUMENT_READ);

    public static final Set<String> READ_ONLY_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> !e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    public static final Set<String> MUTATING_TOOLS = CAPABILITIES.entrySet().stream()
            .filter(e -> e.getValue().isMutating()).map(Map.Entry::getKey).collect(Collectors.toUnmodifiableSet());

    private final ObjectProvider<EmbeddingBackfillService> backfillProvider;
    private final ReorganizationPlanService planService;
    private final AiProperties aiProperties;
    private final AiToolRolePolicy rolePolicy;

    public EmbeddingAiToolsContributor(ObjectProvider<EmbeddingBackfillService> backfillProvider,
                                       ReorganizationPlanService planService,
                                       AiProperties aiProperties, AiToolRolePolicy rolePolicy) {
        this.backfillProvider = backfillProvider;
        this.planService = planService;
        this.aiProperties = aiProperties;
        this.rolePolicy = rolePolicy;
    }

    @Override
    public Object bind(String userEmail, Authentication authentication) {
        // Cheap to build (no model is resolved), so the definitions template resolves it too
        return new EmbeddingAiTools(backfillProvider.getIfAvailable(), planService, aiProperties, rolePolicy)
                .forUser(userEmail, authentication);
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
