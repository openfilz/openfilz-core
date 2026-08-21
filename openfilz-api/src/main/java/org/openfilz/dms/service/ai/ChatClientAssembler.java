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
        return ChatClient.builder(chatModel)
                .defaultSystem(aiProperties.getSystemPrompt())
                .defaultToolCallbacks(MethodToolCallbackProvider.builder()
                        .toolObjects(documentAiTools)
                        .build())
                .defaultAdvisors(ToolCallingAdvisor.builder()
                        .toolCallingManager(toolCallingManager)
                        .build())
                .build();
    }
}
