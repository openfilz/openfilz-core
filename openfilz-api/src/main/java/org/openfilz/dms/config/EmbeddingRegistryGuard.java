package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Enforces that the embedding model is a one-time deployment decision.
 * <p>
 * Every vector in {@code vector_store} was produced by one specific embedding model. Vectors from
 * different models live in incomparable vector spaces: querying with a different model does not
 * degrade similarity search, it silently breaks it. This guard records the configured provider and
 * model in {@code ai_embedding_registry} on first AI-enabled startup, and on every later startup
 * compares the current configuration against that record:
 * <ul>
 *   <li>match — nothing to do;</li>
 *   <li>mismatch while {@code vector_store} is empty — the choice is simply updated (nothing is
 *       invalidated);</li>
 *   <li>mismatch while indexed vectors exist — startup fails with the two ways out (restore the
 *       previous configuration, or wipe the vector store and re-embed). Set
 *       {@code openfilz.ai.embedding.validation=warn} to start anyway with degraded RAG.</li>
 * </ul>
 * The model's dimensions are also checked against the fixed {@code vector(768)} column
 * ({@link AiConfig#EMBEDDING_DIMENSIONS}), turning a raw SQL insert error at first upload into an
 * explicit startup message.
 * <p>
 * When the {@code spring.ai.model.embedding} selector is {@code none} or absent, the embedding
 * model's identity cannot be established (mocked or externally-managed setups) and the guard skips.
 * <p>
 * Only a <em>detected</em> violation fails startup. If the registry cannot be reached at all (JDBC
 * pool down, table missing), that is logged at ERROR and startup continues: an unreachable database
 * is no evidence that the model changed, and crash-looping the whole DMS over an unverifiable AI
 * invariant costs far more than the degraded RAG it would prevent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingRegistryGuard implements ApplicationRunner {

    static final String EMBEDDING_SELECTOR = "spring.ai.model.embedding";
    static final String NONE = "none";

    // ObjectProvider: the guard bean is eager (ApplicationRunner) but must start even when the
    // AI feature is off at runtime and neither the JdbcTemplate nor the EmbeddingModel can be
    // created (native images bake bean conditions at build time, so the toggle is runtime-only).
    private final ObjectProvider<JdbcTemplate> aiJdbcTemplateProvider;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final Environment environment;
    private final AiProperties aiProperties;

    private JdbcTemplate aiJdbcTemplate;
    private EmbeddingModel embeddingModel;

    @Override
    public void run(ApplicationArguments args) {
        if (!aiProperties.isActive()) {
            log.debug("[AI-EMBED] AI feature is disabled — skipping embedding registry check");
            return;
        }
        String provider = environment.getProperty(EMBEDDING_SELECTOR);
        if (provider == null || NONE.equals(provider)) {
            log.debug("[AI-EMBED] No embedding provider selected ({}={}) — skipping embedding registry check",
                    EMBEDDING_SELECTOR, provider);
            return;
        }
        this.aiJdbcTemplate = aiJdbcTemplateProvider.getIfAvailable();
        this.embeddingModel = embeddingModelProvider.getIfAvailable();
        if (aiJdbcTemplate == null || embeddingModel == null) {
            log.warn("[AI-EMBED] AI is active but the JdbcTemplate/EmbeddingModel are unavailable — "
                    + "skipping embedding registry check");
            return;
        }

        try {
            checkRegistry(provider);
        } catch (IllegalStateException e) {
            // A real violation (model mismatch / wrong dimensions) — startup MUST fail, that is
            // the whole point of this guard.
            throw e;
        } catch (RuntimeException e) {
            // The registry could not be read/written at all (JDBC pool down, table missing, ...).
            // That says nothing about whether the embedding model changed, so it is not a reason
            // to take the whole DMS down: degrade like resolveDimensions() does and let the API
            // serve documents. AI stays configured; the one-time-decision guard is simply not
            // enforced on this boot.
            log.error("[AI-EMBED] Could not verify the embedding registry — the DMS is starting anyway, but the "
                    + "embedding-model change guard is NOT enforced this boot. If the cause below is a bare "
                    + "NullPointerException from HikariPool, the underlying connection error was dropped by "
                    + "HikariCP 7.x and is only visible with logging.level.com.zaxxer.hikari=DEBUG.", e);
        }
    }

    private void checkRegistry(String provider) {
        String model = environment.getProperty("spring.ai." + provider + ".embedding.model", "unknown");
        Integer dimensions = resolveDimensions();
        validateSchemaDimensions(provider, model, dimensions);

        List<Map<String, Object>> rows = aiJdbcTemplate.queryForList(
                "SELECT provider, model FROM ai_embedding_registry WHERE id = 1");
        Long vectorCount = aiJdbcTemplate.queryForObject("SELECT count(*) FROM vector_store", Long.class);
        long vectors = vectorCount == null ? 0 : vectorCount;

        if (rows.isEmpty()) {
            aiJdbcTemplate.update(
                    "INSERT INTO ai_embedding_registry (id, provider, model, dimensions) VALUES (1, ?, ?, ?)",
                    provider, model, dimensions);
            if (vectors > 0) {
                log.warn("[AI-EMBED] Recorded embedding model {}/{} for {} pre-existing vector(s) — "
                                + "assuming the configuration is unchanged since they were indexed",
                        provider, model, vectors);
            } else {
                log.info("[AI-EMBED] Recorded embedding model {}/{} — treat this as a one-time deployment "
                        + "decision: changing it later requires re-embedding every document", provider, model);
            }
            return;
        }

        Map<String, Object> recorded = rows.getFirst();
        String recordedProvider = (String) recorded.get("provider");
        String recordedModel = (String) recorded.get("model");
        if (provider.equals(recordedProvider) && model.equals(recordedModel)) {
            log.debug("[AI-EMBED] Embedding model {}/{} matches the registry", provider, model);
            return;
        }

        if (vectors == 0) {
            aiJdbcTemplate.update(
                    "UPDATE ai_embedding_registry SET provider = ?, model = ?, dimensions = ?, updated_at = now() WHERE id = 1",
                    provider, model, dimensions);
            log.info("[AI-EMBED] Embedding model changed from {}/{} to {}/{} — accepted, the vector store is empty",
                    recordedProvider, recordedModel, provider, model);
            return;
        }

        String message = String.format(
                "The configured embedding model (%s/%s) does not match the model that indexed the %d existing "
                        + "vector(s) in vector_store (%s/%s). Vectors from different embedding models are not "
                        + "comparable — similarity search would silently return wrong results. Either restore the "
                        + "previous embedding configuration, or wipe the vector store (TRUNCATE TABLE vector_store; "
                        + "DELETE FROM ai_embedding_registry) and re-embed every document by re-uploading it. "
                        + "Set openfilz.ai.embedding.validation=warn to start anyway with degraded RAG results.",
                provider, model, vectors, recordedProvider, recordedModel);
        handleViolation(message);
    }

    /**
     * The model's dimension count when it can be established without risk: unknown models make
     * Spring AI call the provider to embed a probe string, so any failure (provider down at
     * startup) degrades to "unknown" instead of blocking the DMS.
     */
    private Integer resolveDimensions() {
        try {
            int dimensions = embeddingModel.dimensions();
            return dimensions > 0 ? dimensions : null;
        } catch (Exception e) {
            log.warn("[AI-EMBED] Could not determine embedding dimensions ({}) — skipping dimension validation",
                    e.getMessage());
            return null;
        }
    }

    private void validateSchemaDimensions(String provider, String model, Integer dimensions) {
        if (dimensions == null || dimensions == AiConfig.EMBEDDING_DIMENSIONS) {
            return;
        }
        handleViolation(String.format(
                "Embedding model %s/%s produces %d-dimensional vectors, but the vector_store schema is fixed at "
                        + "vector(%d). Configure a model (or a dimensions option, e.g. "
                        + "spring.ai.openai.embedding.dimensions) that produces %d-dimensional vectors.",
                provider, model, dimensions, AiConfig.EMBEDDING_DIMENSIONS, AiConfig.EMBEDDING_DIMENSIONS));
    }

    private void handleViolation(String message) {
        if (aiProperties.getEmbedding().getValidation() == AiProperties.EmbeddingConfig.Validation.WARN) {
            log.error("[AI-EMBED] {}", message);
            return;
        }
        throw new IllegalStateException(message);
    }
}
