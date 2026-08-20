package org.openfilz.dms.config;

import org.openfilz.dms.service.ai.DocumentAiTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Spring AI configuration.
 * Only active when openfilz.ai.active=true.
 * <p>
 * PgVectorStore auto-configuration is excluded globally (application.yml) because
 * the project uses R2DBC (no auto-created JdbcTemplate). When AI is active,
 * this config creates the JDBC DataSource, JdbcTemplate, and PgVectorStore manually.
 * <p>
 * Both the Ollama and the OpenAI starter are on the classpath, but only one provider is
 * auto-configured at a time: {@link AiModelProviderEnvironmentPostProcessor} derives Spring AI's
 * {@code spring.ai.model.chat} / {@code spring.ai.model.embedding} selectors from the OpenFilz
 * {@code openfilz.ai.<provider>.<kind>.enabled} switches, so a single ChatModel and a single
 * EmbeddingModel bean reach this configuration.
 */
@Configuration
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiConfig {

    /**
     * Vector dimension of the {@code vector_store.embedding} column (see
     * {@code db/ai-migration/V1_4__add_ai_support.sql}). Every configured embedding model must
     * produce vectors of exactly this size — {@link EmbeddingRegistryGuard} enforces it at startup.
     */
    public static final int EMBEDDING_DIMENSIONS = 768;

    @Bean
    ChatClient chatClient(ChatModel chatModel, AiProperties aiProperties, DocumentAiTools documentAiTools,
                          ToolCallingManager toolCallingManager) {
        // Spring AI 2.0 moved tool execution out of the ChatModel and into a ToolCallingAdvisor
        // on the ChatClient. Only the auto-configured ChatClient.Builder registers that advisor;
        // this client is built from the raw ChatClient.builder(ChatModel) factory, so it has to be
        // registered explicitly — without it the model emits tool-call requests nobody executes.
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

    @Bean
    DataSource aiDataSource(
            @Value("${spring.flyway.url}") String jdbcUrl,
            @Value("${spring.flyway.user}") String username,
            @Value("${spring.flyway.password}") String password) {
        return DataSourceBuilder.create()
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    JdbcTemplate aiJdbcTemplate(DataSource aiDataSource) {
        return new JdbcTemplate(aiDataSource);
    }

    @Bean
    VectorStore vectorStore(JdbcTemplate aiJdbcTemplate, EmbeddingModel embeddingModel) {
        return PgVectorStore.builder(aiJdbcTemplate, embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false) // Flyway manages the schema
                .build();
    }
}
