package org.openfilz.dms.e2e;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiConfig;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.EmbeddingRegistryGuard;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Runs {@link EmbeddingRegistryGuard} against the real Flyway-managed schema
 * ({@code ai_embedding_registry}, {@code vector_store}) to validate its SQL end-to-end.
 * The context itself pins every {@code spring.ai.model.*} selector to "none" (mocked models),
 * which the startup-time guard bean skips — each test drives a manually-built guard instead.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(AiTestConfig.class)
public class EmbeddingRegistryGuardIT extends TestContainersBaseConfig {

    @Autowired
    private JdbcTemplate aiJdbcTemplate;

    public EmbeddingRegistryGuardIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void configureAiProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.ai.active", () -> true);
        registry.add("spring.ai.openai.api-key", () -> "test-dummy-key");
        registry.add("spring.ai.model.chat", () -> "none");
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    @BeforeEach
    void cleanTables() {
        aiJdbcTemplate.update("DELETE FROM ai_embedding_registry");
        aiJdbcTemplate.update("DELETE FROM vector_store");
    }

    private EmbeddingRegistryGuard guard(String provider, String model) {
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.ai.model.embedding", provider);
        environment.setProperty("spring.ai." + provider + ".embedding.model", model);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModel.dimensions()).thenReturn(AiConfig.EMBEDDING_DIMENSIONS);
        AiProperties aiProperties = new AiProperties();
        aiProperties.setActive(true); // the guard self-checks the runtime AI flag now
        return new EmbeddingRegistryGuard(provider(aiJdbcTemplate), provider(embeddingModel), environment, aiProperties);
    }

    private static <T> org.springframework.beans.factory.ObjectProvider<T> provider(T instance) {
        return new org.springframework.beans.factory.ObjectProvider<>() {
            @Override
            public T getIfAvailable() {
                return instance;
            }
        };
    }

    private void insertVector() {
        String zeros = "[" + "0,".repeat(AiConfig.EMBEDDING_DIMENSIONS - 1) + "0]";
        aiJdbcTemplate.update(
                "INSERT INTO vector_store (content, metadata, embedding) VALUES ('test', '{}'::json, ?::vector)",
                zeros);
    }

    private Map<String, Object> registryRow() {
        return aiJdbcTemplate.queryForMap("SELECT provider, model, dimensions FROM ai_embedding_registry WHERE id = 1");
    }

    @Test
    void firstStartup_recordsConfiguredModel() {
        guard("ollama", "nomic-embed-text").run(null);

        Map<String, Object> row = registryRow();
        Assertions.assertEquals("ollama", row.get("provider"));
        Assertions.assertEquals("nomic-embed-text", row.get("model"));
        Assertions.assertEquals(AiConfig.EMBEDDING_DIMENSIONS, row.get("dimensions"));
    }

    @Test
    void sameModelOnLaterStartup_isAccepted() {
        guard("ollama", "nomic-embed-text").run(null);
        insertVector();

        Assertions.assertDoesNotThrow(() -> guard("ollama", "nomic-embed-text").run(null));
    }

    @Test
    void modelChangeWithEmptyVectorStore_updatesRegistry() {
        guard("ollama", "nomic-embed-text").run(null);

        guard("openai", "text-embedding-3-small").run(null);

        Map<String, Object> row = registryRow();
        Assertions.assertEquals("openai", row.get("provider"));
        Assertions.assertEquals("text-embedding-3-small", row.get("model"));
    }

    @Test
    void modelChangeWithIndexedVectors_refusesToStart() {
        guard("ollama", "nomic-embed-text").run(null);
        insertVector();

        IllegalStateException error = Assertions.assertThrows(IllegalStateException.class,
                () -> guard("openai", "text-embedding-3-small").run(null));

        Assertions.assertTrue(error.getMessage().contains("ollama/nomic-embed-text"));
        // The record of what actually indexed the vectors must survive the rejected change.
        Assertions.assertEquals("ollama", registryRow().get("provider"));
    }
}
