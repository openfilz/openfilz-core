package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Configuration properties for the AI document chat feature.
 * Maps to openfilz.ai.* properties in application.yml.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.ai")
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiProperties {

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
     * Ollama provider switches.
     */
    private Provider ollama = new Provider();

    /**
     * OpenAI provider switches.
     */
    private Provider openai = new Provider();

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
