package org.openfilz.dms.service.ai;

import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

/**
 * A {@link ChatModel} bound to one caller that picks its model the way the chat assistant does:
 * every call resolves the caller's model ({@link UserChatClientResolver#resolve} — their BYOK
 * model, else the server default) and runs through {@link AiFallbackChain#callWithFailover}.
 * <p>
 * For the tool front-ends that have no chat turn of their own to fail over in — the MCP server,
 * whose {@code describeImage} used to call the bare auto-configured bean. In a native image that
 * bean is whatever the build compiled in (Ollama), so a deployment whose real models are a
 * fallback chain (pool keys only) answered every MCP vision call with the compiled Ollama
 * model's 404 ("model 'llama3' not found"), while the in-app chat — which fails over — worked.
 * <p>
 * Resolution happens at call time, not construction: a tools instance is bound for every MCP
 * call, and only {@code describeImage} ever reaches this model. Blocking by design — tool methods
 * run on a tool thread and block throughout.
 */
public class FailoverChatModel implements ChatModel {

    private static final String TAG = "VISION";

    private final UserChatClientResolver resolver;
    private final AiFallbackChain fallbackChain;
    private final String userEmail;

    public FailoverChatModel(UserChatClientResolver resolver, AiFallbackChain fallbackChain, String userEmail) {
        this.resolver = resolver;
        this.fallbackChain = fallbackChain;
        this.userEmail = userEmail;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ResolvedChat primary = resolver.resolve(userEmail).block();
        if (primary == null) {
            throw new IllegalStateException("No chat model is configured on this deployment.");
        }
        return fallbackChain.callWithFailover(primary, TAG, candidate -> candidate.chatModel().call(prompt));
    }
}
