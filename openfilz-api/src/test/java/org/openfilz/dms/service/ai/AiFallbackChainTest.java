package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.service.ai.AiFailoverPolicy.Failure;
import org.openfilz.dms.service.ai.UserChatClientResolver.ResolvedChat;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Candidate ordering, per-provider key rotation and cooldown behaviour of the fallback chain.
 * <p>
 * Real providers meter quota <em>per API key, per model</em>, so {@link SimulatedProviders}
 * does the same: it is the piece that makes "rotate the key and the model works again" a thing
 * this test can actually observe, rather than something asserted about the configuration alone.
 * {@code ask()} mirrors {@code AiChatServiceImpl#streamWithFailover} — walk the candidates,
 * classify each failure, bench it, move on — so the end-to-end cases exercise the same loop
 * production runs, without needing the whole reactive chat pipeline.
 * <p>
 * Time is injected rather than slept on: cooldown expiry is asserted instantly.
 */
class AiFallbackChainTest {

    private static final Instant T0 = Instant.parse("2026-08-22T00:00:00Z");

    private static final String GOOGLE_A = "google-key-A";
    private static final String GOOGLE_B = "google-key-B";
    private static final String ANTHROPIC_X = "anthropic-key-X";
    private static final String ANTHROPIC_Y = "anthropic-key-Y";

    private AiProperties properties;
    private MockEnvironment environment;
    private UserChatClientResolver resolver;
    private AiFallbackChain chain;

    @BeforeEach
    void setUp() {
        properties = new AiProperties();
        properties.getFallback().setEnabled(true);
        environment = new MockEnvironment();
        resolver = mock(UserChatClientResolver.class);
        when(resolver.buildChatModel(any(), anyString(), any(), anyString()))
                .thenAnswer(invocation -> mock(ChatModel.class));
        chain = new AiFallbackChain(properties, resolver, environment);
    }

    // ------------------------------------------------------------------ helpers

    private void chain(String... entries) {
        properties.getFallback().setChain(List.of(entries));
    }

    private void keys(AiProvider provider, String... apiKeys) {
        Map<AiProvider, List<String>> pools =
                new LinkedHashMap<>(properties.getFallback().getKeys());
        pools.put(provider, Arrays.asList(apiKeys));
        properties.getFallback().setKeys(pools);
    }

    /** The server-default model, as {@code UserChatClientResolver} hands it over. */
    private ResolvedChat primary(String model, String apiKey) {
        return new ResolvedChat(mock(ChatModel.class), "google-genai", model, AiKeyRef.of(apiKey));
    }

    /**
     * A primary parked out of the way so a test can observe the chain alone.
     * <p>
     * Benched for a year, because a cooldown that lapsed mid-test would silently put the primary
     * back at the head of the candidate list and quietly invalidate the assertion. The configured
     * cooldown is restored afterwards — the expiry instant is computed when {@code trip} runs, so
     * the long bench survives the restore.
     */
    private ResolvedChat benchedPrimary() {
        ResolvedChat primary = new ResolvedChat(mock(ChatModel.class), "none", "no-primary", AiKeyRef.UNKNOWN);
        Duration configured = properties.getFallback().getUnavailableCooldown();
        properties.getFallback().setUnavailableCooldown(Duration.ofDays(365));
        chain.trip(primary, Failure.MODEL_UNAVAILABLE, T0);
        properties.getFallback().setUnavailableCooldown(configured);
        return primary;
    }

    /** Readable "PROVIDER/model/key" rendering of a candidate list. */
    private List<String> view(List<ResolvedChat> candidates) {
        Map<String, String> names = Map.of(
                AiKeyRef.of(GOOGLE_A), GOOGLE_A, AiKeyRef.of(GOOGLE_B), GOOGLE_B,
                AiKeyRef.of(ANTHROPIC_X), ANTHROPIC_X, AiKeyRef.of(ANTHROPIC_Y), ANTHROPIC_Y);
        return candidates.stream()
                .map(c -> c.provider() + "/" + c.model() + "/" + names.getOrDefault(c.keyRef(), c.keyRef()))
                .toList();
    }

    private ResolvedChat entry(AiProvider provider, String model, String apiKey) {
        return new ResolvedChat(mock(ChatModel.class), provider.name(), model, AiKeyRef.of(apiKey));
    }

    // ------------------------------------------------------------------ ordering

    @Test
    @DisplayName("failover off leaves the primary as the only candidate")
    void disabledByDefault() {
        properties.getFallback().setEnabled(false);
        chain("google:gemini-3.6-flash");
        ResolvedChat primary = primary("gemini-2.5-flash", GOOGLE_A);
        assertThat(chain.candidates(primary)).containsExactly(primary);
    }

    @Test
    @DisplayName("a provider is exhausted across all its models and keys before the next is tried")
    void exhaustsProviderBeforeMovingOn() {
        chain("google:gemini-3.6-flash", "google:gemini-2.0-flash", "anthropic:claude-haiku-4-5");
        keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X, ANTHROPIC_Y);

        assertThat(view(chain.candidates(primary("gemini-2.5-flash", GOOGLE_A), T0))).containsExactly(
                "google-genai/gemini-2.5-flash/google-key-A",
                "GOOGLE/gemini-3.6-flash/google-key-A",
                "GOOGLE/gemini-2.0-flash/google-key-A",
                "GOOGLE/gemini-3.6-flash/google-key-B",
                "GOOGLE/gemini-2.0-flash/google-key-B",
                "ANTHROPIC/claude-haiku-4-5/anthropic-key-X",
                "ANTHROPIC/claude-haiku-4-5/anthropic-key-Y");
    }

    @Test
    @DisplayName("a model is only ever paired with a key belonging to its own provider")
    void neverCrossesKeysBetweenProviders() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);

        List<String> candidates = view(chain.candidates(benchedPrimary(), T0));

        assertThat(candidates).allSatisfy(candidate -> {
            if (candidate.startsWith("GOOGLE")) assertThat(candidate).contains("google-key");
            if (candidate.startsWith("ANTHROPIC")) assertThat(candidate).contains("anthropic-key");
        });
        assertThat(candidates).hasSize(3);
    }

    // ------------------------------------------------------------------ key rotation

    @Nested
    @DisplayName("key rotation")
    class KeyRotation {

        @BeforeEach
        void chainWithTwoGoogleKeys() {
            chain("google:m1", "google:m2", "anthropic:c1");
            keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
            keys(AiProvider.ANTHROPIC, ANTHROPIC_X);
        }

        @Test
        @DisplayName("the key is kept while any of that provider's models still has quota")
        void keepsKeyWhileOneModelWorks() {
            chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);

            List<String> candidates = view(chain.candidates(benchedPrimary(), T0.plusSeconds(1)));

            assertThat(candidates).contains("GOOGLE/m2/google-key-A");   // key A still in use
            assertThat(candidates).doesNotContain("GOOGLE/m1/google-key-A");
            assertThat(candidates).contains("GOOGLE/m1/google-key-B");   // and m1 salvaged on B
        }

        @Test
        @DisplayName("the key rotates once every model of that provider is spent on it")
        void rotatesKeyWhenProviderIsExhausted() {
            chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);
            chain.trip(entry(AiProvider.GOOGLE, "m2", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);

            List<String> candidates = view(chain.candidates(benchedPrimary(), T0.plusSeconds(1)));

            assertThat(candidates).noneMatch(candidate -> candidate.endsWith("google-key-A"));
            assertThat(candidates).containsExactly(
                    "GOOGLE/m1/google-key-B", "GOOGLE/m2/google-key-B", "ANTHROPIC/c1/anthropic-key-X");
        }

        @Test
        @DisplayName("rotating google's key does not disturb anthropic's own key choice")
        void rotationIsPerProvider() {
            chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);
            chain.trip(entry(AiProvider.GOOGLE, "m2", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);

            assertThat(view(chain.candidates(benchedPrimary(), T0.plusSeconds(1))))
                    .contains("ANTHROPIC/c1/anthropic-key-X");
        }
    }

    // ------------------------------------------------------------------ end-to-end with simulated quota

    /**
     * Stands in for the provider endpoints. Quota is metered per (key, model) exactly as the free
     * tiers charge it, so rotating a key genuinely restores a model here.
     */
    private static final class SimulatedProviders {
        private final Map<String, Integer> remaining = new HashMap<>();
        private final Set<String> retiredModels = new HashSet<>();
        private final Set<String> revokedKeys = new HashSet<>();
        private final List<String> calls = new ArrayList<>();

        void allow(String apiKey, String model, int callCount) {
            remaining.put(AiKeyRef.of(apiKey) + "|" + model, callCount);
        }

        /** Answer, or throw what that provider would throw. */
        String call(ResolvedChat candidate) {
            String id = candidate.provider() + "|" + candidate.keyRef() + "|" + candidate.model();
            calls.add(id);
            if (revokedKeys.contains(candidate.keyRef())) {
                throw wrapped("401 . API key not valid. Please pass a valid API key.");
            }
            if (retiredModels.contains(candidate.model())) {
                throw wrapped("404 . This model models/" + candidate.model()
                        + " is no longer available to new users.");
            }
            String quotaKey = candidate.keyRef() + "|" + candidate.model();
            Integer left = remaining.get(quotaKey);
            if (left != null) {
                if (left <= 0) {
                    throw wrapped("429 . RESOURCE_EXHAUSTED. You exceeded your current quota.");
                }
                remaining.put(quotaKey, left - 1);
            }
            return id;
        }

        /** Spring AI wraps every provider failure in a generic runtime exception. */
        private static RuntimeException wrapped(String providerMessage) {
            return new RuntimeException("Failed to generate content", new RuntimeException(providerMessage));
        }

        long callsMatching(String fragment) {
            return calls.stream().filter(call -> call.contains(fragment)).count();
        }
    }

    private final SimulatedProviders providers = new SimulatedProviders();

    /** One chat request, mirroring {@code AiChatServiceImpl#streamWithFailover}. */
    private String ask(ResolvedChat primary, Instant now) {
        for (ResolvedChat candidate : chain.candidates(primary, now)) {
            try {
                return providers.call(candidate);
            } catch (RuntimeException e) {
                Failure failure = AiFailoverPolicy.classify(e);
                chain.trip(candidate, failure, now);
                if (!failure.shouldFailover()) {
                    return "ERROR:" + e.getCause().getMessage();
                }
            }
        }
        return "NO_CANDIDATE";
    }

    @Test
    @DisplayName("requests drain the first key's models, rotate to the second, then change provider")
    void drainsKeysThenProviders() {
        chain("google:m1", "google:m2", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);
        ResolvedChat primary = benchedPrimary();

        // Free-tier shape: one call per (key, model) on Google; Anthropic is plentiful.
        providers.allow(GOOGLE_A, "m1", 1);
        providers.allow(GOOGLE_A, "m2", 1);
        providers.allow(GOOGLE_B, "m1", 1);
        providers.allow(GOOGLE_B, "m2", 1);

        List<String> answers = new ArrayList<>();
        for (int request = 0; request < 6; request++) {
            answers.add(ask(primary, T0.plusSeconds(10 + request)));
        }

        assertThat(answers).containsExactly(
                "GOOGLE|" + AiKeyRef.of(GOOGLE_A) + "|m1",
                "GOOGLE|" + AiKeyRef.of(GOOGLE_A) + "|m2",
                "GOOGLE|" + AiKeyRef.of(GOOGLE_B) + "|m1",
                "GOOGLE|" + AiKeyRef.of(GOOGLE_B) + "|m2",
                "ANTHROPIC|" + AiKeyRef.of(ANTHROPIC_X) + "|c1",
                "ANTHROPIC|" + AiKeyRef.of(ANTHROPIC_X) + "|c1");
    }

    @Test
    @DisplayName("a spent quota costs one failing call in total, not one per request")
    void benchingStopsRepeatedFailingCalls() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);
        ResolvedChat primary = benchedPrimary();
        providers.allow(GOOGLE_A, "m1", 0);      // already out of quota

        for (int request = 0; request < 5; request++) {
            ask(primary, T0.plusSeconds(10 + request));
        }

        assertThat(providers.callsMatching("|m1")).isEqualTo(1);
        assertThat(providers.callsMatching("|c1")).isEqualTo(5);
    }

    @Test
    @DisplayName("a cooldown expires on its own and the key returns to rotation")
    void cooldownExpiryReturnsKeyToRotation() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);
        properties.getFallback().setQuotaCooldown(Duration.ofMinutes(5));
        ResolvedChat primary = benchedPrimary();
        providers.allow(GOOGLE_A, "m1", 1);

        assertThat(ask(primary, T0.plusSeconds(10))).contains("|m1");
        assertThat(ask(primary, T0.plusSeconds(11))).contains("|c1");   // google now spent

        providers.allow(GOOGLE_A, "m1", 1);                             // provider window refilled
        assertThat(ask(primary, T0.plusSeconds(120))).contains("|c1");  // still benched
        assertThat(ask(primary, T0.plusSeconds(700))).contains("|m1");  // cooldown served
    }

    @Test
    @DisplayName("a retired model is benched after one 404 and the next model answers")
    void retiredModelIsBenchedAfterOneAttempt() {
        chain("google:m1", "google:m2");
        keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
        ResolvedChat primary = benchedPrimary();
        providers.retiredModels.add("m1");

        assertThat(ask(primary, T0.plusSeconds(10))).endsWith("|m2");
        assertThat(providers.callsMatching("|m1")).isEqualTo(1);
    }

    @Test
    @DisplayName("a revoked key surfaces as an error instead of quietly rotating to the next one")
    void revokedKeyDoesNotFailOver() {
        chain("google:m1");
        keys(AiProvider.GOOGLE, GOOGLE_A, GOOGLE_B);
        ResolvedChat primary = benchedPrimary();
        providers.revokedKeys.add(AiKeyRef.of(GOOGLE_A));

        assertThat(ask(primary, T0.plusSeconds(10))).startsWith("ERROR:401");
        // Not benched either — the operator must keep seeing the real error until they fix the key.
        assertThat(view(chain.candidates(primary, T0.plusSeconds(11)))).contains("GOOGLE/m1/google-key-A");
    }

    // ------------------------------------------------------------------ configuration handling

    @Test
    @DisplayName("a provider with no pool keeps using its single spring.ai.*.api-key")
    void emptyPoolFallsBackToTheServerKey() {
        chain("google:m1");
        environment.setProperty("spring.ai.google.genai.api-key", "legacy-single-key");

        List<ResolvedChat> candidates = chain.candidates(benchedPrimary(), T0);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.getFirst().keyRef()).isEqualTo(AiKeyRef.of("legacy-single-key"));
    }

    @Test
    @DisplayName("the 'disabled' sentinel counts as no key at all")
    void disabledSentinelYieldsNoCandidates() {
        chain("google:m1");
        environment.setProperty("spring.ai.google.genai.api-key", "disabled");
        ResolvedChat primary = benchedPrimary();

        // No usable chain candidate: the primary is handed back rather than nothing at all, so the
        // request fails with a real provider error instead of a synthetic "no model" one.
        assertThat(chain.candidates(primary, T0)).containsExactly(primary);
    }

    @Test
    @DisplayName("blank, null and duplicate pool entries are cleaned up")
    void cleansUpThePool() {
        chain("google:m1");
        keys(AiProvider.GOOGLE, GOOGLE_A, "   ", GOOGLE_A, null, GOOGLE_B);

        assertThat(view(chain.candidates(benchedPrimary(), T0)))
                .containsExactly("GOOGLE/m1/google-key-A", "GOOGLE/m1/google-key-B");
    }

    @Test
    @DisplayName("the primary is not offered twice when the chain names the same model and key")
    void deduplicatesPrimaryAgainstTheChain() {
        chain("google:gemini-3.6-flash");
        keys(AiProvider.GOOGLE, GOOGLE_A);

        ResolvedChat primary = primary("gemini-3.6-flash", GOOGLE_A);

        assertThat(chain.candidates(primary, T0)).containsExactly(primary);
    }

    @Test
    @DisplayName("malformed and unknown chain entries are ignored without breaking the chain")
    void ignoresMalformedEntries() {
        chain("", "no-separator", "mystery:model", "anthropic:c1");
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);

        assertThat(view(chain.candidates(benchedPrimary(), T0)))
                .containsExactly("ANTHROPIC/c1/anthropic-key-X");
    }

    @Test
    @DisplayName("a provider client that refuses to build is skipped, not fatal")
    void survivesAClientThatCannotBeBuilt() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);
        when(resolver.buildChatModel(eq(AiProvider.GOOGLE), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("no credentials"));

        assertThat(view(chain.candidates(benchedPrimary(), T0)))
                .containsExactly("ANTHROPIC/c1/anthropic-key-X");
    }

    @Test
    @DisplayName("the primary is retried rather than refused when everything is cooling down")
    void fallsBackToPrimaryWhenAllBenched() {
        chain("google:m1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        ResolvedChat primary = primary("gemini-3.6-flash", GOOGLE_A);

        chain.trip(primary, Failure.QUOTA_EXHAUSTED, T0);
        chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.QUOTA_EXHAUSTED, T0);

        assertThat(chain.candidates(primary, T0.plusSeconds(60))).containsExactly(primary);
    }

    // ------------------------------------------------------------------ cooldown semantics

    @Test
    @DisplayName("a retired model is benched far longer than a spent quota")
    void retiredModelGetsTheLongCooldown() {
        chain("google:m1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        properties.getFallback().setQuotaCooldown(Duration.ofMinutes(5));
        properties.getFallback().setUnavailableCooldown(Duration.ofHours(6));
        ResolvedChat primary = benchedPrimary();

        chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.MODEL_UNAVAILABLE, T0);

        // Well past the quota cooldown, still benched — a 404 model does not come back by itself.
        assertThat(chain.candidates(primary, T0.plusSeconds(3600))).containsExactly(primary);
        assertThat(view(chain.candidates(primary, T0.plus(Duration.ofHours(7)))))
                .containsExactly("GOOGLE/m1/google-key-A");
    }

    @Test
    @DisplayName("a credential failure never benches anything")
    void authFailureDoesNotBench() {
        chain("google:m1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        ResolvedChat primary = benchedPrimary();

        chain.trip(entry(AiProvider.GOOGLE, "m1", GOOGLE_A), Failure.NOT_FAILOVER, T0);

        assertThat(view(chain.candidates(primary, T0))).containsExactly("GOOGLE/m1/google-key-A");
    }

    @Test
    @DisplayName("two BYOK users on the same provider and model do not share a cooldown")
    void byokUsersHaveIndependentCooldowns() {
        ResolvedChat userOne = entry(AiProvider.GOOGLE, "gemini-3.6-flash", "user-one-key");
        ResolvedChat userTwo = entry(AiProvider.GOOGLE, "gemini-3.6-flash", "user-two-key");

        chain.trip(userOne, Failure.QUOTA_EXHAUSTED, T0);

        assertThat(chain.isHealthy(
                AiFallbackChain.cooldownKey("GOOGLE", userOne.keyRef(), "gemini-3.6-flash"), T0.plusSeconds(1)))
                .isFalse();
        assertThat(chain.isHealthy(
                AiFallbackChain.cooldownKey("GOOGLE", userTwo.keyRef(), "gemini-3.6-flash"), T0.plusSeconds(1)))
                .isTrue();
    }

    // ------------------------------------------------------------------ local models are never failed over

    @Test
    @DisplayName("a local Ollama model is never failed over to a cloud provider")
    void localModelsAreNeverFailedOver() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);

        ResolvedChat ollama = new ResolvedChat(mock(ChatModel.class), "ollama", "qwen2.5", AiKeyRef.UNKNOWN);

        // Data residency, not performance: an operator running a local LLM does so because
        // document content must not leave the deployment, and the RAG context sent with every
        // question *is* document text. A local outage is to be fixed, not routed around.
        assertThat(chain.candidates(ollama)).containsExactly(ollama);
    }

    @Test
    @DisplayName("a BYOK user on a cloud provider still gets failover on an Ollama deployment")
    void byokCloudUsersStillGetFailover() {
        chain("google:m1", "anthropic:c1");
        keys(AiProvider.GOOGLE, GOOGLE_A);
        keys(AiProvider.ANTHROPIC, ANTHROPIC_X);

        // Their content already leaves the building by their own choice, so the local-only rule
        // does not apply to them — which is why the test is on the model in use, not on the
        // server-wide selector.
        ResolvedChat byok = entry(AiProvider.GOOGLE, "gemini-3.6-flash", "user-key");

        assertThat(chain.candidates(byok)).hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("provider spelling differences resolve to the same cooldown entry")
    void normalisesProviderSpelling() {
        assertThat(AiFallbackChain.cooldownKey("google-genai", "ref", "gemini-3.6-flash"))
                .isEqualTo(AiFallbackChain.cooldownKey("GOOGLE", "ref", "Gemini-3.6-Flash"));
    }

    @Test
    @DisplayName("a fingerprint identifies a key without revealing it")
    void fingerprintsDoNotLeakKeys() {
        String fingerprint = AiKeyRef.of("AIzaSy-super-secret-key");

        assertThat(fingerprint).doesNotContain("AIzaSy", "secret").hasSize(8);
        assertThat(fingerprint).isEqualTo(AiKeyRef.of("AIzaSy-super-secret-key"));
        assertThat(fingerprint).isNotEqualTo(AiKeyRef.of("AIzaSy-super-secret-kez"));
        assertThat(AiKeyRef.of("  ")).isEqualTo(AiKeyRef.UNKNOWN);
        assertThat(AiKeyRef.of(null)).isEqualTo(AiKeyRef.UNKNOWN);
    }
}
