package org.openfilz.dms.config;

import org.springframework.ai.embedding.EmbeddingModel;
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

    // Note: there is deliberately no ChatClient bean. Chat clients are assembled per request by
    // ChatClientAssembler (system prompt + per-request DocumentAiTools + ToolCallingAdvisor) from
    // the ChatModel resolved for the user — the server-default model bean, or a BYOK model from
    // UserChatClientResolver. Assembly is cheap; the models carry the pooled HTTP clients.

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
                .dimensions(768)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false) // Flyway manages the schema
                .build();
    }
}
