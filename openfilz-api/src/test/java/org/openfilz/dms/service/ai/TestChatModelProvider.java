package org.openfilz.dms.service.ai;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * An {@link ObjectProvider} over a single (possibly absent) {@code ChatModel}, so tests can build a
 * {@link UserChatClientResolver} both ways.
 * <p>
 * {@link #none()} is the one that matters: a deployment with {@code spring.ai.model.chat=none} has
 * no {@code ChatModel} bean at all — the MCP-only and the "light, no LLM" profiles — and the
 * resolver has to be constructible there, since the insight and smart-filing services depend on it
 * even when their classifier never calls a model.
 */
final class TestChatModelProvider implements ObjectProvider<ChatModel> {

    private final ChatModel model;

    private TestChatModelProvider(ChatModel model) {
        this.model = model;
    }

    static ObjectProvider<ChatModel> of(ChatModel model) {
        return new TestChatModelProvider(model);
    }

    /** No chat model bean in the context. */
    static ObjectProvider<ChatModel> none() {
        return new TestChatModelProvider(null);
    }

    @Override
    public ChatModel getObject() {
        if (model == null) {
            throw new NoSuchBeanDefinitionException(ChatModel.class);
        }
        return model;
    }

    @Override
    public ChatModel getObject(Object... args) {
        return getObject();
    }

    @Override
    public ChatModel getIfAvailable() {
        return model;
    }

    @Override
    public ChatModel getIfUnique() {
        return model;
    }
}
