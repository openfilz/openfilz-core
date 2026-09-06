package org.openfilz.dms.bench;

import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.EmbeddingModels;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.setup.OpenAiSetup;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.transformers.TransformersEmbeddingModel;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

/**
 * Compares the embedding providers OpenFilz can run — Ollama, an OpenAI-compatible server such
 * as TEI, and the in-process ONNX Runtime — on the same documents: latency per document, batch
 * throughput on one and several threads, the memory the in-process model costs, and whether
 * two providers serving the same model family produce the same vectors (the cosine between
 * their embeddings of the same text — what decides whether a library can switch provider
 * without re-embedding).
 * <p>
 * Corpus: any directory of documents Tika reads (sub-directories included); {@code bench.dir}.
 * <pre>
 * mvn -pl openfilz-api test -Dtest=EmbeddingProviderBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dbench.dir=/path/to/docs [-Dbench.providers=onnx,ollama,openai] [-Dbench.openai.url=http://localhost:8080]
 * </pre>
 * Settings: {@code bench.providers} (default {@code onnx,ollama}), {@code bench.ollama.url},
 * {@code bench.embedding} (Ollama model, {@code nomic-embed-text}), {@code bench.onnx.model}
 * and {@code bench.onnx.tokenizer} (URIs, default nomic-embed-text-v1.5 quantised from Hugging
 * Face), {@code bench.onnx.cache} (directory), {@code bench.openai.url} / {@code bench.openai.model}
 * (an OpenAI-compatible embedding server, e.g. TEI), {@code bench.threads} ({@code 4}),
 * {@code bench.batch} ({@code 16}), {@code bench.max-chars} ({@code 2000}), {@code bench.limit}.
 */
@EnabledIfSystemProperty(named = "bench.dir", matches = ".+")
class EmbeddingProviderBenchmark {

    private record Provider(String name, EmbeddingModel model, AutoCloseable closer) {
    }

    private final StringBuilder report = new StringBuilder();
    private int printed;

    @Test
    void run() throws Exception {
        Path root = Path.of(prop("bench.dir", ""));
        int maxChars = Integer.parseInt(prop("bench.max-chars", "2000"));
        int limit = Integer.parseInt(prop("bench.limit", "0"));
        int threads = Integer.parseInt(prop("bench.threads", "4"));
        int batch = Integer.parseInt(prop("bench.batch", "16"));
        List<String> texts = load(root, limit, maxChars);
        line("# Embedding provider benchmark");
        line("");
        line(String.format(Locale.ROOT, "Corpus: `%s` — %d documents, heads of at most %d characters; %d threads, batches of %d.",
                root, texts.size(), maxChars, threads, batch));
        line("");

        Map<String, List<float[]>> vectorsByProvider = new LinkedHashMap<>();
        for (String name : prop("bench.providers", "onnx,ollama").split(",")) {
            Provider provider;
            long rssBefore = usedMemory();
            long t0 = System.nanoTime();
            try {
                provider = provider(name.trim());
            } catch (Exception e) {
                line("## " + name.trim());
                line("");
                line("- not available: " + e.getClass().getSimpleName() + " " + firstLine(e.getMessage()));
                line("");
                flush();
                continue;
            }
            // warm-up: model load, JIT, connection
            provider.model().embed("warm up");
            long loadMillis = (System.nanoTime() - t0) / 1_000_000;
            long rssAfter = usedMemory();

            // one document at a time, one thread — the upload path
            List<Long> single = new ArrayList<>();
            List<float[]> vectors = new ArrayList<>();
            for (String text : texts) {
                long s = System.nanoTime();
                vectors.add(provider.model().embed(text));
                single.add(System.nanoTime() - s);
            }
            vectorsByProvider.put(provider.name(), vectors);

            // batches, one thread — the backfill path
            long b0 = System.nanoTime();
            for (int from = 0; from < texts.size(); from += batch) {
                provider.model().embed(texts.subList(from, Math.min(texts.size(), from + batch)));
            }
            double batchDocsPerSecond = texts.size() / ((System.nanoTime() - b0) / 1e9);

            // batches, several threads — several uploads or replicas at once
            ExecutorService pool = Executors.newFixedThreadPool(threads);
            long p0 = System.nanoTime();
            List<Future<?>> futures = new ArrayList<>();
            for (int from = 0; from < texts.size(); from += batch) {
                List<String> slice = texts.subList(from, Math.min(texts.size(), from + batch));
                futures.add(pool.submit(() -> provider.model().embed(slice)));
            }
            for (Future<?> f : futures) f.get();
            double parallelDocsPerSecond = texts.size() / ((System.nanoTime() - p0) / 1e9);
            pool.shutdown();

            line("## " + provider.name());
            line("");
            line(String.format(Locale.ROOT, "- ready in %d ms (model load + first call), %d dimensions, %+.0f MB of heap held after loading",
                    loadMillis, vectors.getFirst().length, (rssAfter - rssBefore) / 1e6));
            line(String.format(Locale.ROOT, "- one document at a time: mean %.0f ms, p50 %.0f ms, p95 %.0f ms, max %.0f ms",
                    single.stream().mapToLong(Long::longValue).average().orElse(0) / 1e6, percentile(single, 0.5) / 1e6,
                    percentile(single, 0.95) / 1e6, single.stream().mapToLong(Long::longValue).max().orElse(0) / 1e6));
            line(String.format(Locale.ROOT, "- batches of %d, one thread: %.1f documents/s; %d threads: %.1f documents/s",
                    batch, batchDocsPerSecond, threads, parallelDocsPerSecond));
            line("");
            flush();
            if (provider.closer() != null) {
                provider.closer().close();
            }
        }

        // the same model through two providers: the same vectors?
        List<String> names = new ArrayList<>(vectorsByProvider.keySet());
        if (names.size() >= 2) {
            line("## Same vectors across providers?");
            line("");
            line("| providers | mean cosine of the same text | min | texts |");
            line("|---|---|---|---|");
            for (int i = 0; i < names.size(); i++) {
                for (int j = i + 1; j < names.size(); j++) {
                    List<float[]> a = vectorsByProvider.get(names.get(i));
                    List<float[]> b = vectorsByProvider.get(names.get(j));
                    double sum = 0, min = 1;
                    int n = Math.min(a.size(), b.size());
                    for (int k = 0; k < n; k++) {
                        double c = cosine(a.get(k), b.get(k));
                        sum += c;
                        min = Math.min(min, c);
                    }
                    line(String.format(Locale.ROOT, "| %s vs %s | %.4f | %.4f | %d |", names.get(i), names.get(j), sum / n, min, n));
                }
            }
            line("");
            line("Above ~0.99 the two are the same embedding space for OpenFilz's purposes (neighbours, votes, "
                    + "classification): a library can switch provider with EmbeddingRegistryGuard's validation set to "
                    + "warn and no re-embedding. Below, re-embed.");
            line("");
        }

        Path out = Path.of("target", "bench", "embedding-benchmark-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        flush();
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private Provider provider(String name) throws Exception {
        switch (name) {
            case "onnx" -> {
                AiProperties.Transformers.Embedding config = new AiProperties.Transformers.Embedding();
                config.setModelUri(prop("bench.onnx.model", config.getModelUri()));
                config.setTokenizerUri(prop("bench.onnx.tokenizer", config.getTokenizerUri()));
                config.setCacheDirectory(prop("bench.onnx.cache", ""));
                config.setModelOutputName(prop("bench.onnx.output", config.getModelOutputName()));
                TransformersEmbeddingModel model = EmbeddingModels.buildTransformers(config);
                return new Provider("onnx in-process (" + Path.of(prop("bench.onnx.model", "model_quantized.onnx")).getFileName() + ")", model, model);
            }
            case "ollama" -> {
                JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory();
                http.setReadTimeout(Duration.ofMinutes(5));
                OllamaApi api = OllamaApi.builder().baseUrl(prop("bench.ollama.url", "http://localhost:11434"))
                        .restClientBuilder(RestClient.builder().requestFactory(http)).build();
                String embedding = prop("bench.embedding", "nomic-embed-text");
                return new Provider("ollama (" + embedding + ")", OllamaEmbeddingModel.builder().ollamaApi(api)
                        .options(OllamaEmbeddingOptions.builder().model(embedding).build()).build(), null);
            }
            case "openai" -> {
                String url = prop("bench.openai.url", "");
                if (url.isEmpty()) throw new IllegalArgumentException("bench.openai.url is not set");
                String modelName = prop("bench.openai.model", "nomic-ai/nomic-embed-text-v1.5");
                return new Provider("openai-compatible " + url + " (" + modelName + ")", OpenAiEmbeddingModel.builder()
                        .openAiClient(OpenAiSetup.setupSyncClient(url, prop("bench.openai.key", "unused"), null, null, null, null,
                                false, false, modelName, Duration.ofMinutes(5), 1, null, null, ObservationRegistry.NOOP, null, List.of()))
                        .metadataMode(MetadataMode.NONE)
                        .options(OpenAiEmbeddingOptions.builder().model(modelName).build())
                        .build(), null);
            }
            default -> throw new IllegalArgumentException("unknown provider " + name + " (onnx, ollama, openai)");
        }
    }

    private static long usedMemory() {
        System.gc();
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long percentile(List<Long> values, double p) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.isEmpty() ? 0 : sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * sorted.size())));
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static List<String> load(Path root, int limit, int maxChars) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("bench.dir is not a directory: " + root);
        }
        Tika tika = new Tika();
        tika.setMaxStringLength(maxChars * 2);
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                if (file.getFileName().toString().startsWith(".")) continue;
                if (limit > 0 && out.size() >= limit) break;
                try {
                    String text = tika.parseToString(file);
                    if (text != null && !text.isBlank()) {
                        out.add(text.length() > maxChars ? text.substring(0, maxChars) : text);
                    }
                } catch (Exception e) {
                    System.out.println("  ! cannot read " + file + ": " + firstLine(e.getMessage()));
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no readable document under " + root);
        }
        return out;
    }

    private static String firstLine(String message) {
        if (message == null) return "";
        int nl = message.indexOf('\n');
        return nl < 0 ? message : message.substring(0, nl);
    }

    private static String prop(String name, String fallback) {
        String value = System.getProperty(name);
        if (value == null || value.isEmpty()) {
            value = System.getenv(name.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_'));
        }
        return value == null || value.isEmpty() ? fallback : value;
    }

    private void line(String text) {
        report.append(text).append('\n');
    }

    private void flush() {
        System.out.print(report.substring(printed));
        printed = report.length();
    }
}
