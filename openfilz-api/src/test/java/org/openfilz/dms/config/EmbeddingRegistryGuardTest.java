package org.openfilz.dms.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Pins the decision table of {@link EmbeddingRegistryGuard}: the embedding model is recorded on
 * first startup and any later change is rejected (or warned about) while indexed vectors exist,
 * because vectors from different embedding models are not comparable.
 */
class EmbeddingRegistryGuardTest {

    private static final String SELECT_REGISTRY = "SELECT provider, model FROM ai_embedding_registry WHERE id = 1";
    private static final String COUNT_VECTORS = "SELECT count(*) FROM vector_store";

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
    private final MockEnvironment environment = new MockEnvironment();
    private final AiProperties aiProperties = new AiProperties();

    private final EmbeddingRegistryGuard guard =
            new EmbeddingRegistryGuard(jdbcTemplate, embeddingModel, environment, aiProperties);

    @BeforeEach
    void defaults() {
        when(embeddingModel.dimensions()).thenReturn(AiConfig.EMBEDDING_DIMENSIONS);
    }

    private void givenOllamaNomic() {
        environment.setProperty("spring.ai.model.embedding", "ollama");
        environment.setProperty("spring.ai.ollama.embedding.model", "nomic-embed-text");
    }

    private void givenRegistry(String provider, String model) {
        when(jdbcTemplate.queryForList(SELECT_REGISTRY))
                .thenReturn(List.of(Map.of("provider", provider, "model", model)));
    }

    private void givenEmptyRegistry() {
        when(jdbcTemplate.queryForList(SELECT_REGISTRY)).thenReturn(List.of());
    }

    private void givenVectorCount(long count) {
        when(jdbcTemplate.queryForObject(COUNT_VECTORS, Long.class)).thenReturn(count);
    }

    /** Selector "none" means a mocked or externally-managed model — identity unknown, skip. */
    @Test
    void selectorNone_skips() {
        environment.setProperty("spring.ai.model.embedding", "none");

        guard.run(null);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void selectorAbsent_skips() {
        guard.run(null);

        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void freshInstall_recordsConfiguredModel() {
        givenOllamaNomic();
        givenEmptyRegistry();
        givenVectorCount(0);

        guard.run(null);

        verify(jdbcTemplate).update(startsWith("INSERT INTO ai_embedding_registry"),
                eq("ollama"), eq("nomic-embed-text"), eq(AiConfig.EMBEDDING_DIMENSIONS));
    }

    /** Pre-guard install: vectors exist but were never recorded — assume the config is unchanged. */
    @Test
    void preRegistryInstallWithVectors_recordsWithoutFailing() {
        givenOllamaNomic();
        givenEmptyRegistry();
        givenVectorCount(42);

        assertDoesNotThrow(() -> guard.run(null));

        verify(jdbcTemplate).update(startsWith("INSERT INTO ai_embedding_registry"),
                eq("ollama"), eq("nomic-embed-text"), eq(AiConfig.EMBEDDING_DIMENSIONS));
    }

    @Test
    void matchingModel_isSilent() {
        givenOllamaNomic();
        givenRegistry("ollama", "nomic-embed-text");
        givenVectorCount(42);

        guard.run(null);

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    @Test
    void modelChangedWithEmptyVectorStore_updatesRegistry() {
        environment.setProperty("spring.ai.model.embedding", "openai");
        environment.setProperty("spring.ai.openai.embedding.model", "text-embedding-3-small");
        givenRegistry("ollama", "nomic-embed-text");
        givenVectorCount(0);

        guard.run(null);

        verify(jdbcTemplate).update(startsWith("UPDATE ai_embedding_registry"),
                eq("openai"), eq("text-embedding-3-small"), eq(AiConfig.EMBEDDING_DIMENSIONS));
    }

    @Test
    void modelChangedWithIndexedVectors_failsFast() {
        givenOllamaNomic();
        givenRegistry("openai", "text-embedding-3-small");
        givenVectorCount(42);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guard.run(null));

        assertTrue(error.getMessage().contains("ollama/nomic-embed-text"));
        assertTrue(error.getMessage().contains("openai/text-embedding-3-small"));
        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    /** The escape hatch: an admin accepting degraded RAG results still gets a loud log. */
    @Test
    void modelChangedWithIndexedVectors_warnModeStartsAnyway() {
        aiProperties.getEmbedding().setValidation(AiProperties.EmbeddingConfig.Validation.WARN);
        givenOllamaNomic();
        givenRegistry("openai", "text-embedding-3-small");
        givenVectorCount(42);

        assertDoesNotThrow(() -> guard.run(null));

        verify(jdbcTemplate, never()).update(anyString(), any(), any(), any());
    }

    /** A model whose vectors can't fit vector_store's vector(768) column must fail up front. */
    @Test
    void wrongDimensions_failsFast() {
        environment.setProperty("spring.ai.model.embedding", "openai");
        environment.setProperty("spring.ai.openai.embedding.model", "text-embedding-3-large");
        when(embeddingModel.dimensions()).thenReturn(3072);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guard.run(null));

        assertTrue(error.getMessage().contains("3072"));
        assertTrue(error.getMessage().contains(String.valueOf(AiConfig.EMBEDDING_DIMENSIONS)));
    }

    /** Unknown models make Spring AI probe the provider — a provider that's down must not block the DMS. */
    @Test
    void dimensionsUnavailable_skipsDimensionValidation() {
        givenOllamaNomic();
        when(embeddingModel.dimensions()).thenThrow(new RuntimeException("connection refused"));
        givenEmptyRegistry();
        givenVectorCount(0);

        assertDoesNotThrow(() -> guard.run(null));

        verify(jdbcTemplate).update(startsWith("INSERT INTO ai_embedding_registry"),
                eq("ollama"), eq("nomic-embed-text"), eq(null));
    }

    /** Mockito mocks return 0 from dimensions() — must count as unknown, not as a mismatch. */
    @Test
    void zeroDimensions_treatedAsUnknown() {
        givenOllamaNomic();
        when(embeddingModel.dimensions()).thenReturn(0);
        givenEmptyRegistry();
        givenVectorCount(0);

        assertDoesNotThrow(() -> guard.run(null));

        verify(jdbcTemplate).update(startsWith("INSERT INTO ai_embedding_registry"),
                eq("ollama"), eq("nomic-embed-text"), eq(null));
    }

    @Test
    void failureMessage_namesTheTwoWaysOut() {
        givenOllamaNomic();
        givenRegistry("openai", "text-embedding-3-small");
        givenVectorCount(1);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> guard.run(null));

        assertTrue(error.getMessage().contains("TRUNCATE TABLE vector_store"));
        assertTrue(error.getMessage().contains("openfilz.ai.embedding.validation=warn"));
    }
}
