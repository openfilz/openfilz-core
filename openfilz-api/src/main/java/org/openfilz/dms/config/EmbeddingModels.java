package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * The embedding model OpenFilz actually uses — the one seam every consumer (the vector store,
 * the registry guard, the category classifiers) goes through, so the provider can be chosen at
 * runtime in a native image too.
 * <p>
 * Two sources: the model Spring AI auto-configured for the {@code spring.ai.model.embedding}
 * selector (Ollama, OpenAI or an OpenAI-compatible server such as TEI), and the <b>in-process</b>
 * one — nomic-embed-text-v1.5 (or any ONNX model) run inside the API through ONNX Runtime,
 * {@code TRANSFORMERS_EMBEDDING_ENABLED=true}. In a JVM image the selector already excludes the
 * other providers when the in-process one is enabled. In the enterprise native image the
 * selector is fixed at build time (Ollama), so the in-process model is built here, at runtime,
 * from the flag alone: no bean condition, the pattern every runtime toggle of this code base
 * follows. It is built once, on first use, and never registered as a bean — a second
 * {@code EmbeddingModel} bean would make every by-type injection ambiguous.
 * <p>
 * Each API replica holds its own copy of the model and embeds on its own CPUs — the provider
 * scales with the API, nothing else to deploy. Measurements against Ollama and an embedding
 * server: docs/ai.md §2.
 */
@Slf4j
@Component
@Lazy
public class EmbeddingModels {

    public static final String TRANSFORMERS_PROVIDER = "transformers";

    private final ObjectProvider<EmbeddingModel> autoconfigured;
    private final AiProperties aiProperties;
    private volatile EmbeddingModel inProcess;

    public EmbeddingModels(ObjectProvider<EmbeddingModel> autoconfigured, AiProperties aiProperties) {
        this.autoconfigured = autoconfigured;
        this.aiProperties = aiProperties;
    }

    /** Is the in-process provider the one in use? */
    public boolean inProcess() {
        return aiProperties.getTransformers().getEmbedding().isEnabled();
    }

    /** The provider name as the registry records it. */
    public String provider(String selector) {
        return inProcess() ? TRANSFORMERS_PROVIDER : selector;
    }

    /** The effective embedding model, or null when none is configured. */
    public EmbeddingModel effective() {
        if (inProcess()) {
            EmbeddingModel model = inProcess;
            if (model == null) {
                synchronized (this) {
                    if (inProcess == null) {
                        inProcess = buildTransformers(aiProperties.getTransformers().getEmbedding());
                    }
                    model = inProcess;
                }
            }
            return model;
        }
        return autoconfigured.getIfAvailable();
    }

    /**
     * The in-process model from its configuration: the ONNX file and the tokenizer are fetched
     * once into the cache directory, then loaded; the token embeddings of {@code model-output-name}
     * are mean-pooled into the vector. Shared with the benchmarks so they measure the very object
     * the API runs.
     */
    public static TransformersEmbeddingModel buildTransformers(AiProperties.Transformers.Embedding config) {
        try {
            TransformersEmbeddingModel model = new TransformersEmbeddingModel(MetadataMode.NONE);
            model.setModelResource(config.getModelUri());
            model.setTokenizerResource(config.getTokenizerUri());
            if (config.getModelOutputName() != null && !config.getModelOutputName().isBlank()) {
                model.setModelOutputName(config.getModelOutputName());
            }
            if (config.getCacheDirectory() != null && !config.getCacheDirectory().isBlank()) {
                model.setResourceCacheDirectory(config.getCacheDirectory());
            }
            model.setDisableCaching(!config.isCacheEnabled());
            if (config.getGpuDeviceId() >= 0) {
                model.setGpuDeviceId(config.getGpuDeviceId());
            }
            long started = System.currentTimeMillis();
            model.afterPropertiesSet();
            log.info("[AI-EMBED] in-process embedding model '{}' ready in {} ms ({} dimensions) — {}", config.getModel(),
                    System.currentTimeMillis() - started, model.dimensions(), config.getModelUri());
            return model;
        } catch (Exception e) {
            throw new IllegalStateException("The in-process embedding model could not be loaded from "
                    + config.getModelUri() + ": " + e.getMessage(), e);
        }
    }
}
