package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import org.openfilz.dms.enums.AiProvider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration properties for the AI document chat feature.
 * Maps to openfilz.ai.* properties in application.yml.
 * <p>
 * Deliberately NOT gated on {@code openfilz.ai.active}: in GraalVM native images bean
 * conditions are evaluated at build time, so the whole AI feature is toggled at runtime —
 * the beans always exist ({@code @Lazy} where their dependencies require the AI providers)
 * and the entry points consult {@link #isActive()} per request.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.ai")
public class AiProperties {

    /**
     * Master runtime switch for the whole AI feature. Read at runtime (never as a bean
     * condition) so it stays toggleable in GraalVM native images.
     */
    private boolean active = false;

    /**
     * The system prompt used by the AI assistant.
     */
    private String systemPrompt = """
            You are OpenFilz AI Assistant, a helpful document management assistant.
            You help users find, organize, and understand their documents stored in OpenFilz.
            You can search for documents, summarize content, reorganize folders, and answer questions about document contents.
            Always be concise and helpful. When performing actions, confirm what you did.
            If you are unsure about an action, ask the user to confirm before proceeding.
            """;

    /**
     * Embedding configuration.
     */
    private EmbeddingConfig embedding = new EmbeddingConfig();

    /**
     * Automatic failover to another chat model when the configured one runs out of quota.
     */
    private Fallback fallback = new Fallback();

    /**
     * Ollama provider switches.
     */
    private Provider ollama = new Provider();

    /**
     * OpenAI provider switches.
     */
    private Provider openai = new Provider();

    /**
     * Anthropic (Claude) provider switches. Chat only — Anthropic has no embeddings API,
     * so {@code anthropic.embedding.enabled} is ignored (embeddings resolve to Ollama/OpenAI).
     */
    private Provider anthropic = new Provider();

    /**
     * Google Gemini provider switches (GenAI / Gemini Developer API, API-key auth).
     * Chat only in OpenFilz: {@code google.embedding.enabled} is ignored — the pgvector
     * schema is pinned to the 768-dim output of the Ollama/OpenAI embedding models.
     */
    private Provider google = new Provider();

    /**
     * Per-provider switches deciding which Spring AI model auto-configuration is activated.
     * Consumed by {@link AiModelProviderEnvironmentPostProcessor}, which turns them into the
     * {@code spring.ai.model.chat} / {@code spring.ai.model.embedding} selectors Spring AI 2.0
     * gates its provider auto-configurations on.
     */
    @Data
    public static class Provider {

        private Toggle chat = new Toggle();

        private Toggle embedding = new Toggle();

        @Data
        public static class Toggle {
            /**
             * Whether this provider serves that kind of model.
             */
            private boolean enabled = false;
        }
    }

    /**
     * Chat-model failover: what to try when the configured model refuses to answer.
     * <p>
     * Aimed squarely at free provider tiers, whose per-minute and per-day allowances are small
     * enough to hit during normal use. When a chat call fails with an exhausted quota, a retired
     * model, or an unreachable provider, OpenFilz retries the same question on the next model in
     * {@link #chain} and benches the failed one for a cooldown so later requests skip it outright.
     * See {@code AiFallbackChain} and {@code AiFailoverPolicy}.
     * <p>
     * A credential failure never triggers failover — answering from a different model would hide
     * a broken API key instead of surfacing it.
     */
    @Data
    public static class Fallback {

        /** Master switch; off by default so existing deployments behave exactly as before. */
        private boolean enabled = false;

        /**
         * Models to fall back to, in order, as {@code provider:model} entries — for example
         * {@code google:gemini-3.6-flash,anthropic:claude-haiku-4-5,openai:gpt-4o-mini}.
         * Providers: {@code google}, {@code anthropic}, {@code openai}, {@code openai-compatible};
         * each needs its server API key configured, and entries without one are skipped with a
         * warning. The active chat model is always tried first and needs no entry here.
         */
        private List<String> chain = new ArrayList<>();

        /**
         * Additional API keys per provider, tried in order — the answer to a free tier whose
         * quota is charged <em>per key</em> rather than per model.
         * <p>
         * Once every {@link #chain} model for a provider is out of quota on the key in use, the
         * next key in that provider's pool takes over and those models are available again. Each
         * provider keeps its own pool, so a chain that mixes providers always reaches for the key
         * belonging to whichever provider it lands on.
         * <p>
         * Leave a provider's pool empty to keep using its single {@code spring.ai.*.api-key}.
         */
        private Map<AiProvider, List<String>> keys = new LinkedHashMap<>();

        /**
         * How long a model is benched after an exhausted quota, an overloaded provider, or a
         * connection failure. Short by design: per-minute allowances refill quickly, and an
         * expired cooldown silently returns the model to rotation.
         */
        private Duration quotaCooldown = Duration.ofMinutes(5);

        /**
         * How long a model is benched after a 404 (retired, renamed, or not enabled for this key).
         * Much longer than {@link #quotaCooldown} because that model is not coming back on its
         * own — the cooldown just stops every request paying for the same 404 until an operator
         * updates the configuration.
         */
        private Duration unavailableCooldown = Duration.ofHours(6);

        /**
         * How startup reacts when the chain names a provider the deployment has no API key for.
         * FAIL_FAST (default) refuses to start; WARN logs and carries on with a shorter chain.
         * <p>
         * Fail-fast is safe as a default because failover is opt-in: only a deployment that
         * configured a chain can trip it, and a chain entry that can never be built is a typo,
         * not a decision — better caught at boot than on the first exhausted quota.
         */
        private Validation validation = Validation.FAIL_FAST;

        public enum Validation {
            FAIL_FAST, WARN
        }
    }

    /**
     * Chunk size for splitting documents before embedding.
     */
    @Data
    public static class EmbeddingConfig {
        /**
         * Default chunk size in characters for text splitting.
         */
        private int chunkSize = 1000;

        /**
         * Overlap between chunks in characters to preserve context.
         */
        private int chunkOverlap = 200;

        /**
         * Maximum number of similar chunks to retrieve for RAG context.
         */
        private int topK = 5;

        /**
         * Minimum similarity threshold (0.0 - 1.0) for vector search results.
         */
        private double similarityThreshold = 0.7;

        /**
         * How {@link EmbeddingRegistryGuard} reacts when the configured embedding model no longer
         * matches the one that indexed the existing vectors (or its dimensions don't fit the
         * vector_store schema). FAIL_FAST (default) refuses to start; WARN logs an error and
         * starts anyway, accepting degraded RAG results.
         */
        private Validation validation = Validation.FAIL_FAST;

        public enum Validation {
            FAIL_FAST, WARN
        }
    }
}
