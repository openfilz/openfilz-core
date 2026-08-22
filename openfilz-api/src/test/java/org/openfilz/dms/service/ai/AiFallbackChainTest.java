package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.ai.AiFailoverPolicy.Failure;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Candidate ordering and cooldown behaviour of the chat-model fallback chain.
 * <p>
 * Time is injected rather than slept on: every assertion about a cooldown expiring runs instantly.
 */
class AiFallbackChainTest {

    private static final Instant T0 = Instant.parse("2026-08-22T00:00:00Z");

    private AiProperties properties;
    private MockEnvironment environment;
    private UserChatClientResolver resolver;
    private AiFallbackChain chain;

    /** The server default the deployment starts from: Google, as in the reported deployment. */
    private ResolvedChat primary;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getFallback().setEnabled(true);
        properties.getFallback().setChain(List.of("google:gemini-3.6-flash", "anthropic:claude-haiku-4-5"));

        environment = new MockEnvironment();
        environment.setProperty("spring.ai.google.genai.api-key", "google-key");
        environment.setProperty("spring.ai.anthropic.api-key", "anthropic-key");

        resolver = mock(UserChatClientResolver.class);
        when(resolver.buildChatModel(any(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> mock(ChatModel.class));

        chain = new AiFallbackChain(properties, resolver, environment);

        // Note the provider spelling: the server default arrives as Spring AI's selector name
        // ("google-genai"), while chain entries use the AiProvider name ("GOOGLE").
        primary = new ResolvedChat(mock(ChatModel.class), "google-genai", "gemini-2.5-flash");
    }

    @Test
    @DisplayName("failover off leaves the primary as the only candidate")
    void disabledByDefault() {
        properties.getFallback().setEnabled(false);
        assertThat(chain.candidates(primary)).containsExactly(primary);
    }

    @Test
    @DisplayName("healthy primary leads, fallbacks follow in configured order")
    void ordersCandidates() {
        List<ResolvedChat> candidates = chain.candidates(primary, T0);

        assertThat(candidates).hasSize(3);
        assertThat(candidates.getFirst()).isSameAs(primary);
        assertThat(candidates.get(1).model()).isEqualTo("gemini-3.6-flash");
        assertThat(candidates.get(2).model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("a benched model is skipped, so later requests stop paying for its failure")
    void skipsCoolingDownPrimary() {
        chain.trip(primary, Failure.QUOTA_EXHAUSTED, T0);

        List<ResolvedChat> candidates = chain.candidates(primary, T0.plusSeconds(60));

        assertThat(candidates).doesNotContain(primary);
        assertThat(candidates.getFirst().model()).isEqualTo("gemini-3.6-flash");
    }

    @Test
    @DisplayName("a quota cooldown expires on its own and the model returns to rotation")
    void quotaCooldownExpires() {
        properties.getFallback().setQuotaCooldown(Duration.ofMinutes(5));
        chain.trip(primary, Failure.QUOTA_EXHAUSTED, T0);

        assertThat(chain.candidates(primary, T0.plusSeconds(299))).doesNotContain(primary);
        assertThat(chain.candidates(primary, T0.plusSeconds(301)).getFirst()).isSameAs(primary);
    }

    @Test
    @DisplayName("a retired model is benched far longer than a spent quota")
    void retiredModelGetsTheLongCooldown() {
        properties.getFallback().setQuotaCooldown(Duration.ofMinutes(5));
        properties.getFallback().setUnavailableCooldown(Duration.ofHours(6));

        chain.trip(primary, Failure.MODEL_UNAVAILABLE, T0);

        // Well past the quota cooldown, still benched — a 404 model is not coming back by itself.
        assertThat(chain.candidates(primary, T0.plusSeconds(3600))).doesNotContain(primary);
        assertThat(chain.candidates(primary, T0.plus(Duration.ofHours(7))).getFirst()).isSameAs(primary);
    }

    @Test
    @DisplayName("a credential failure never benches a model")
    void doesNotBenchOnAuthFailure() {
        chain.trip(primary, Failure.NOT_FAILOVER, T0);
        assertThat(chain.candidates(primary, T0).getFirst()).isSameAs(primary);
    }

    @Test
    @DisplayName("the primary is retried rather than refused when everything is cooling down")
    void fallsBackToPrimaryWhenAllBenched() {
        properties.getFallback().setChain(List.of("google:gemini-3.6-flash"));
        List<ResolvedChat> candidates = chain.candidates(primary, T0);
        chain.trip(primary, Failure.QUOTA_EXHAUSTED, T0);
        chain.trip(candidates.get(1), Failure.QUOTA_EXHAUSTED, T0);

        assertThat(chain.candidates(primary, T0.plusSeconds(60))).containsExactly(primary);
    }

    @Test
    @DisplayName("chain entries needing an unconfigured API key are skipped, not fatal")
    void skipsProvidersWithoutApiKey() {
        environment.setProperty("spring.ai.openai.api-key", "disabled");  // the application.yml sentinel
        properties.getFallback().setChain(List.of("openai:gpt-4o-mini", "anthropic:claude-haiku-4-5"));

        List<ResolvedChat> candidates = chain.candidates(primary, T0);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(1).model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("malformed and unknown chain entries are ignored without breaking the chain")
    void ignoresMalformedEntries() {
        properties.getFallback().setChain(List.of("", "no-separator", "mystery:model", "anthropic:claude-haiku-4-5"));

        List<ResolvedChat> candidates = chain.candidates(primary, T0);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.get(1).model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("the primary is not offered twice when the chain also lists it")
    void deduplicatesPrimary() {
        // Same model as the primary, spelled with the AiProvider name instead of the selector name.
        properties.getFallback().setChain(List.of("google:gemini-2.5-flash", "anthropic:claude-haiku-4-5"));

        List<ResolvedChat> candidates = chain.candidates(primary, T0);

        assertThat(candidates).hasSize(2);
        assertThat(candidates.getFirst()).isSameAs(primary);
        assertThat(candidates.get(1).model()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    @DisplayName("provider spelling differences resolve to the same cooldown entry")
    void normalisesProviderSpelling() {
        assertThat(AiFallbackChain.key("google-genai", "gemini-3.6-flash"))
                .isEqualTo(AiFallbackChain.key("GOOGLE", "Gemini-3.6-Flash"));
    }
}
