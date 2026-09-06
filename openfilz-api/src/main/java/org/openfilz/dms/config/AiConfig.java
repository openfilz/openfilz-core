package org.openfilz.dms.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SimpleDriverDataSource;

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
public class AiConfig {

    // NOTE: no @ConditionalOnProperty — bean conditions are evaluated at build time in GraalVM
    // native images, so the AI feature must be toggleable at runtime. The beans below are @Lazy:
    // they are only instantiated when an AI entry point actually uses them, which the entry
    // points gate on AiProperties.isActive() at runtime.

    /**
     * Vector dimension of the {@code vector_store.embedding} column (see
     * {@code db/ai-migration/V1_4__add_ai_support.sql}). Every configured embedding model must
     * produce vectors of exactly this size — {@link EmbeddingRegistryGuard} enforces it at startup.
     */
    public static final int EMBEDDING_DIMENSIONS = 768;

    // Note: there is deliberately no ChatClient bean. Chat clients are assembled per request by
    // ChatClientAssembler (system prompt + per-request DocumentAiTools + ToolCallingAdvisor) from
    // the ChatModel resolved for the user — the server-default model bean, or a BYOK model from
    // UserChatClientResolver. Assembly is cheap; the models carry the pooled HTTP clients.

    /**
     * The AI JDBC DataSource, pinned to {@link SimpleDriverDataSource}.
     * <p>
     * {@code DataSourceBuilder.create()} without an explicit {@code type()} resolves to
     * HikariDataSource, because HikariCP is on the classpath transitively via
     * spring-boot-starter-flyway. That pool cannot open a connection inside the EE native image:
     * it fails as a bare NullPointerException, because HikariCP 7.x logs the real cause at DEBUG
     * and then drops it (createPoolEntry returns null without recording the failure, so
     * checkFailFast ends up at PoolInitializationException(null)). EE v1.8.5 crash-looped on this.
     * <p>
     * SimpleDriverDataSource is what Spring Boot itself hands Flyway, and Flyway connects happily
     * in the very image where the pool fails — so this type is known to work there, on the same
     * URL and credentials. Pinning it removes the dependency on Hikari working under native-image.
     * <p>
     * Two caveats, deliberately recorded rather than papered over:
     * <ul>
     *   <li>It is unpooled — every borrow opens a connection. Fine for the startup guard, worth
     *       revisiting if PgVectorStore gets heavy traffic.</li>
     *   <li>Why Hikari fails here is still unknown, and it reportedly works in the CE image. Find
     *       that out (logging.level.com.zaxxer.hikari=DEBUG) before switching the type back.</li>
     * </ul>
     */
    @Bean
    @Lazy
    DataSource aiDataSource(
            @Value("${spring.flyway.url}") String jdbcUrl,
            @Value("${spring.flyway.user}") String username,
            @Value("${spring.flyway.password}") String password) {
        return DataSourceBuilder.create()
                .type(SimpleDriverDataSource.class)
                .url(jdbcUrl)
                .username(username)
                .password(password)
                .build();
    }

    @Bean
    @Lazy
    JdbcTemplate aiJdbcTemplate(DataSource aiDataSource) {
        return new JdbcTemplate(aiDataSource);
    }

    @Bean
    @Lazy
    VectorStore vectorStore(JdbcTemplate aiJdbcTemplate, EmbeddingModels embeddingModels) {
        EmbeddingModel embeddingModel = embeddingModels.effective();
        if (embeddingModel == null) {
            throw new IllegalStateException("No embedding model is configured: enable one provider "
                    + "(TRANSFORMERS_EMBEDDING_ENABLED, OLLAMA_EMBEDDING_ENABLED or OPENAI_EMBEDDING_ENABLED)");
        }
        return PgVectorStore.builder(aiJdbcTemplate, embeddingModel)
                .dimensions(EMBEDDING_DIMENSIONS)
                .distanceType(PgDistanceType.COSINE_DISTANCE)
                .indexType(PgIndexType.HNSW)
                .initializeSchema(false) // Flyway manages the schema
                .build();
    }
}
