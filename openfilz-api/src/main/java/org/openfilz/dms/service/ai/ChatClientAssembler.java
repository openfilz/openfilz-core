package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.AiProperties;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Assembles a {@link ChatClient} from a {@link ChatModel} and a (per-request)
 * {@link DocumentAiTools} instance, with the OpenFilz wiring: system prompt, tool
 * callbacks, and the {@link ToolCallingAdvisor}.
 * <p>
 * Used by the chat pipeline for both the server-default model and per-user BYOK models,
 * so every model gets exactly the same tool wiring. The advisor must be registered
 * explicitly: only the auto-configured {@code ChatClient.Builder} does it on its own, and
 * a client built from {@code ChatClient.builder(chatModel)} would emit tool-call requests
 * nobody executes. Assembly is cheap (no I/O) — the expensive part, the provider
 * {@link ChatModel} with its pooled HTTP client, is what gets cached upstream.
 */
@Component
@RequiredArgsConstructor
@Lazy
public class ChatClientAssembler {

    private final AiProperties aiProperties;
    private final ToolCallingManager toolCallingManager;

    public ChatClient assemble(ChatModel chatModel, DocumentAiTools documentAiTools) {
        return assemble(chatModel, documentAiTools, java.util.List.of());
    }

    /**
     * @param extraTools further per-request tool objects (from the {@code McpToolContributor}s that
     *                   opt into the chat, e.g. the PDF tools), bound to the same user
     */
    public ChatClient assemble(ChatModel chatModel, DocumentAiTools documentAiTools, java.util.List<Object> extraTools) {
        return assemble(chatModel, documentAiTools, extraTools, java.util.Set.of());
    }

    /**
     * @param withheldTools tool names the chat must not bind on top of {@code excluded-tools} — the
     *                      contributors' {@code chatWithheldTools()} (e.g. {@code applyReorganizationPlan},
     *                      confirmed by the user on its card instead), decided per request by the caller
     */
    public ChatClient assemble(ChatModel chatModel, DocumentAiTools documentAiTools, java.util.List<Object> extraTools,
                               java.util.Set<String> withheldTools) {
        Object[] toolObjects = new Object[1 + extraTools.size()];
        toolObjects[0] = documentAiTools;
        for (int i = 0; i < extraTools.size(); i++) {
            toolObjects[i + 1] = extraTools.get(i);
        }
        // openfilz.ai.chat.excluded-tools: drop single tools by name (small local models degrade as
        // the schema grows — see excluded-contributors for whole contributors)
        java.util.Set<String> excluded = new java.util.HashSet<>(withheldTools);
        aiProperties.getChat().getExcludedTools().stream()
                .filter(name -> name != null && !name.isBlank()).map(String::trim).forEach(excluded::add);
        return ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.getSystemPrompt())
                .defaultToolCallbacks(chatCallbacks(toolObjects, excluded))
                .defaultAdvisors(ToolCallingAdvisor.builder()
                        .toolCallingManager(toolCallingManager)
                        .build())
                .build();
    }

    /** The callbacks of the tool objects, minus the excluded names (package-visible for tests). */
    static org.springframework.ai.tool.ToolCallback[] chatCallbacks(Object[] toolObjects, java.util.Set<String> excluded) {
        org.springframework.ai.tool.ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObjects)
                .build()
                .getToolCallbacks();
        if (excluded.isEmpty()) {
            return callbacks;
        }
        return java.util.Arrays.stream(callbacks)
                .filter(callback -> !excluded.contains(callback.getToolDefinition().name()))
                .toArray(org.springframework.ai.tool.ToolCallback[]::new);
    }
}
