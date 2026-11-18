package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.exception.AiNoModelException;
import org.openfilz.dms.service.ai.AiFallbackChain;
import org.openfilz.dms.service.ai.DocumentTextHandoff;
import org.openfilz.dms.service.ai.UserChatClientResolver;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Smart filing's stage-2 model is kept between documents, but a model set, changed or removed at
 * runtime ({@link UserChatClientResolver#defaultModelVersion()} moves) must reach it at once.
 */
class AutoFileModelCacheTest {

    private final UserChatClientResolver resolver = mock(UserChatClientResolver.class);
    private final AiFallbackChain fallbackChain = mock(AiFallbackChain.class);

    private DefaultAutoFileService service() {
        AiProperties properties = new AiProperties();
        return new DefaultAutoFileService(properties, null, null, null, null, null, null, null, null,
                null, null, null, null, resolver, fallbackChain, null, null, null,
                new DocumentTextHandoff(properties), null, null);
    }

    @Test
    @DisplayName("kept while the source version holds, resolved again when it moves")
    void resolvedAgainWhenTheSourceMoves() {
        DefaultAutoFileService service = service();
        ResolvedChat deployment = new ResolvedChat(mock(ChatModel.class), "openai", "gpt-test");
        ResolvedChat organisation = new ResolvedChat(mock(ChatModel.class), "google-genai", "gemini-org");
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.empty());
        when(resolver.defaultModelVersion()).thenReturn(0L);
        when(resolver.resolve(any())).thenReturn(Mono.just(deployment));
        assertThat(service.model()).isSameAs(deployment);

        when(resolver.resolve(any())).thenReturn(Mono.just(organisation));
        assertThat(service.model()).as("same version: the cached model").isSameAs(deployment);

        when(resolver.defaultModelVersion()).thenReturn(1L);
        assertThat(service.model()).as("a model was set at runtime").isSameAs(organisation);
    }

    @Test
    @DisplayName("a removed model stops being used at once: no model left = no stage 2")
    void removedModelStopsAtOnce() {
        DefaultAutoFileService service = service();
        ResolvedChat organisation = new ResolvedChat(mock(ChatModel.class), "google-genai", "gemini-org");
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.empty());
        when(resolver.defaultModelVersion()).thenReturn(1L);
        when(resolver.resolve(any())).thenReturn(Mono.just(organisation));
        assertThat(service.model()).isSameAs(organisation);

        when(resolver.defaultModelVersion()).thenReturn(2L);
        when(resolver.resolve(any())).thenReturn(Mono.error(new AiNoModelException("no model", true)));
        assertThat(service.model()).isNull();
        assertThat(service.model()).as("still none on the next call").isNull();
    }

    @Test
    @DisplayName("the dedicated insights model is kept whatever the runtime source does")
    void dedicatedModelIsPinned() {
        DefaultAutoFileService service = service();
        ResolvedChat dedicated = new ResolvedChat(mock(ChatModel.class), "openai", "gpt-insights");
        when(fallbackChain.configuredModel(any())).thenReturn(Optional.of(dedicated));
        when(resolver.defaultModelVersion()).thenReturn(1L);
        assertThat(service.model()).isSameAs(dedicated);

        when(resolver.defaultModelVersion()).thenReturn(5L);
        assertThat(service.model()).isSameAs(dedicated);
        verify(resolver, never()).resolve(any());
    }
}
