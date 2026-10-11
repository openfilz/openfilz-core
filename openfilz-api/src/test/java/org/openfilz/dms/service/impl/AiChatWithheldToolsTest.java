package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.AiProperties.Tools.DestructiveMode;
import org.openfilz.dms.service.ai.ReorganizationPlanService;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.mcp.McpToolContributor;
import org.openfilz.dms.service.mcp.OrganizeAiToolsContributor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@link AiChatServiceImpl#chatWithheldTools}: the chat honours the contributors'
 * {@code chatWithheldTools()} in {@code confirm-only} mode and ignores them in {@code allow} mode.
 */
class AiChatWithheldToolsTest {

    @SuppressWarnings("unchecked")
    private final McpToolContributor organize = new OrganizeAiToolsContributor(
            mock(ReorganizationPlanService.class), (authentication, capability) -> true, mock(ObjectProvider.class));

    /** A contributor that is NOT exposed in the chat: whatever it withholds is irrelevant there. */
    private final McpToolContributor mcpOnly = new McpToolContributor() {
        @Override
        public Object bind(String userEmail, Authentication authentication) {
            return new Object();
        }

        @Override
        public Map<String, ToolCapability> capabilities() {
            return Map.of("secretTool", ToolCapability.DOCUMENT_WRITE);
        }

        @Override
        public Set<String> chatWithheldTools() {
            return Set.of("secretTool");
        }
    };

    private static AiProperties properties(DestructiveMode mode) {
        AiProperties properties = new AiProperties();
        properties.getTools().setDestructiveMode(mode);
        return properties;
    }

    @Test
    @DisplayName("confirm-only (default): applyReorganizationPlan is withheld from the chat")
    void confirmOnlyWithholdsApply() {
        assertThat(AiChatServiceImpl.chatWithheldTools(List.of(organize, mcpOnly), new AiProperties()))
                .containsExactly("applyReorganizationPlan");
    }

    @Test
    @DisplayName("allow: nothing is withheld — the previous chat behaviour")
    void allowModeWithholdsNothing() {
        assertThat(AiChatServiceImpl.chatWithheldTools(List.of(organize), properties(DestructiveMode.ALLOW))).isEmpty();
    }

    @Test
    @DisplayName("no contributors, nothing withheld")
    void noContributors() {
        assertThat(AiChatServiceImpl.chatWithheldTools(List.of(), new AiProperties())).isEmpty();
        assertThat(AiChatServiceImpl.chatWithheldTools(null, new AiProperties())).isEmpty();
    }
}
