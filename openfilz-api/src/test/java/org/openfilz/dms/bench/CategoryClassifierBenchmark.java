package org.openfilz.dms.bench;

import com.google.genai.Client;
import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction;
import org.openfilz.dms.service.insight.CategoryClassifier.CategoryPrediction.Scored;
import org.openfilz.dms.service.insight.InsightPrompts;
import org.openfilz.dms.service.insight.InsightResult;
import org.openfilz.dms.service.insight.PrototypeCategoryClassifier;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.api.OllamaEmbeddingOptions;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Measures the tier-2 category classifiers on a labelled corpus of your own documents — the
 * question before switching {@code openfilz.ai.insights.classifier.mode} away from the model.
 * <p>
 * Corpus layout: one sub-directory per expected category, the files of that kind inside (any
 * format Tika reads). Run it alone, it is skipped without {@code bench.dir}:
 * <pre>
 * mvn -pl openfilz-api test -Dtest=CategoryClassifierBenchmark -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Dbench.dir=/path/to/corpus [-Dbench.chat=qwen2.5:1.5b,qwen2.5] [-Dbench.google=gemini-2.5-flash-lite]
 * </pre>
 * Settings (system property, or the environment variable in upper snake case):
 * <ul>
 *   <li>{@code bench.dir} — the corpus root (required);</li>
 *   <li>{@code bench.ollama.url} — {@code http://localhost:11434};</li>
 *   <li>{@code bench.embedding} — the Ollama embedding model, {@code nomic-embed-text};</li>
 *   <li>{@code bench.prefixes} — task prefixes to try, {@code |}-separated, default none and {@code classification: };</li>
 *   <li>{@code bench.temperatures} — softmax temperatures to score, default {@code 0.02,0.05,0.1};</li>
 *   <li>{@code bench.chat} — Ollama chat models to compare, comma-separated (none by default);</li>
 *   <li>{@code bench.google} — a Google model to compare; the key is read from {@code GOOGLE_API_KEY} and never printed;</li>
 *   <li>{@code bench.categories} — the closed list, default the deployment's;</li>
 *   <li>{@code bench.max-chars} — text head per document, {@code 2000} for the prototypes, {@code 6000} for the models;</li>
 *   <li>{@code bench.limit} — files per category, {@code 0} = all;</li>
 *   <li>{@code bench.learned} — also score a classifier learned from the corpus itself (nearest centroid, k-NN, leave-one-out), {@code true}.</li>
 * </ul>
 * The report goes to the console and to {@code target/bench/category-benchmark-<time>.md}.
 */
@EnabledIfSystemProperty(named = "bench.dir", matches = ".+")
class CategoryClassifierBenchmark {

    private record Sample(String category, String fileName, String text) {
    }

    private record Outcome(Sample sample, String predicted, double confidence, double bestSimilarity, long nanos) {
        boolean correct() {
            return sample.category().equals(predicted);
        }
    }

    /** The longest sane tier-2 answer; a small model at temperature 0 otherwise loops until its context shifts. */
    private static final int MAX_ANSWER_TOKENS = 512;

    private final StringBuilder report = new StringBuilder();
    private int printed;

    @Test
    void run() throws Exception {
        Path root = Path.of(prop("bench.dir", ""));
        List<String> categories = Arrays.stream(prop("bench.categories", String.join(",",
                        new AiProperties.Insights().getCategories())).split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        int limit = Integer.parseInt(prop("bench.limit", "0"));
        List<Sample> corpus = load(root, limit);
        line("# Category classifier benchmark");
        line("");
        line("Corpus: `" + root + "` — " + corpus.size() + " document(s) in " + corpus.stream().map(Sample::category).distinct().count()
                + " categories; closed list: " + String.join(", ", categories));
        Map<String, Long> perLabel = new TreeMap<>();
        corpus.forEach(s -> perLabel.merge(s.category(), 1L, Long::sum));
        line("Per label: " + perLabel);
        line("");

        // A CPU-only chat model may take a minute per answer: no read timeout of a few seconds here
        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory();
        http.setReadTimeout(Duration.ofMinutes(5));
        OllamaApi ollama = OllamaApi.builder().baseUrl(prop("bench.ollama.url", "http://localhost:11434"))
                .restClientBuilder(RestClient.builder().requestFactory(http)).build();
        String embeddingName = prop("bench.embedding", "nomic-embed-text");
        EmbeddingModel embeddingModel = OllamaEmbeddingModel.builder().ollamaApi(ollama)
                .options(OllamaEmbeddingOptions.builder().model(embeddingName).build()).build();

        // ── prototypes: one embedding pass per prefix, every temperature scored from it ──
        int protoChars = Integer.parseInt(prop("bench.max-chars", "2000"));
        for (String prefix : prop("bench.prefixes", "|classification: ").split("\\|", -1)) {
            AiProperties.Insights.Classifier config = new AiProperties.Insights.Classifier();
            config.setPrefix(prefix);
            config.setMaxChars(protoChars);
            PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(embeddingModel, embeddingName, categories, config);
            classifier.similarities("warm-up.txt", "warm up the model"); // prototypes + model load, outside the timing
            List<Map.Entry<Sample, List<Scored>>> scored = new ArrayList<>();
            List<Long> timings = new ArrayList<>();
            for (Sample sample : corpus) {
                long t0 = System.nanoTime();
                List<Scored> similarities = classifier.similarities(sample.fileName(), sample.text());
                timings.add(System.nanoTime() - t0);
                scored.add(Map.entry(sample, similarities));
            }
            for (String t : prop("bench.temperatures", "0.02,0.05,0.1").split(",")) {
                double temperature = Double.parseDouble(t.trim());
                List<Outcome> outcomes = new ArrayList<>();
                for (int i = 0; i < scored.size(); i++) {
                    CategoryPrediction prediction = PrototypeCategoryClassifier.decide(scored.get(i).getValue(), temperature, 0);
                    double best = prediction.alternatives().isEmpty() ? 0 : prediction.alternatives().getFirst().score();
                    outcomes.add(new Outcome(scored.get(i).getKey(), prediction.category(), prediction.confidence(), best, timings.get(i)));
                }
                summarise("prototype " + embeddingName + (prefix.isEmpty() ? " (no prefix)" : " (prefix \"" + prefix.trim() + "\")")
                        + " T=" + temperature, outcomes, true);
            }
        }

        // ── learned from examples: nearest centroid and k-NN over the corpus itself, leave-one-out ──
        // What a classifier that learns the categories from the library's own labelled documents
        // (the model's or the user's labels) would reach on this corpus — including "other", which
        // a description can never name. Same embedding, no prototype text at all.
        if (!"false".equals(prop("bench.learned", "true"))) {
            List<float[]> vectors = new ArrayList<>();
            List<Long> timings = new ArrayList<>();
            for (Sample sample : corpus) {
                String head = sample.text().length() > protoChars ? sample.text().substring(0, protoChars) : sample.text();
                long t0 = System.nanoTime();
                vectors.add(embeddingModel.embed("File name: " + sample.fileName() + "\n" + head));
                timings.add(System.nanoTime() - t0);
            }
            for (int k : new int[] {0, 1, 3}) {
                List<Outcome> outcomes = new ArrayList<>();
                for (int i = 0; i < corpus.size(); i++) {
                    Map<String, double[]> centroids = new LinkedHashMap<>();
                    List<Map.Entry<String, Double>> scored = new ArrayList<>();
                    for (int j = 0; j < corpus.size(); j++) {
                        if (j == i) continue;
                        double sim = cosine(vectors.get(i), vectors.get(j));
                        scored.add(Map.entry(corpus.get(j).category(), sim));
                        float[] other = vectors.get(j);
                        double[] acc = centroids.computeIfAbsent(corpus.get(j).category(), c -> new double[other.length + 1]);
                        for (int d = 0; d < other.length; d++) acc[d] += other[d];
                        acc[other.length]++;
                    }
                    String predicted;
                    double confidence;
                    if (k == 0) {
                        // nearest centroid: the mean vector of every other document of the category
                        String best = null;
                        double bestSim = -2, second = -2;
                        for (Map.Entry<String, double[]> e : centroids.entrySet()) {
                            double[] acc = e.getValue();
                            float[] centroid = new float[acc.length - 1];
                            for (int d = 0; d < centroid.length; d++) centroid[d] = (float) (acc[d] / acc[acc.length - 1]);
                            double sim = cosine(vectors.get(i), centroid);
                            if (sim > bestSim) { second = bestSim; bestSim = sim; best = e.getKey(); }
                            else if (sim > second) second = sim;
                        }
                        predicted = best;
                        confidence = 1 / (1 + Math.exp(-(bestSim - second) / 0.02));
                    } else {
                        // k nearest documents vote, weighted by similarity
                        scored.sort(Map.Entry.<String, Double>comparingByValue().reversed());
                        Map<String, Double> votes = new LinkedHashMap<>();
                        double total = 0;
                        for (Map.Entry<String, Double> e : scored.subList(0, Math.min(k, scored.size()))) {
                            votes.merge(e.getKey(), e.getValue(), Double::sum);
                            total += e.getValue();
                        }
                        Map.Entry<String, Double> top = votes.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
                        predicted = top.getKey();
                        confidence = total == 0 ? 0 : top.getValue() / total;
                    }
                    outcomes.add(new Outcome(corpus.get(i), predicted, confidence, scored.isEmpty() ? 0 : scored.getFirst().getValue(), timings.get(i)));
                }
                summarise("learned from the corpus, leave-one-out: " + (k == 0 ? "nearest centroid" : k + "-NN"), outcomes, true);
            }
        }

        // ── chat models: the real tier-2 prompt, category only ──
        int modelChars = Integer.parseInt(prop("bench.max-chars", "6000"));
        for (String chat : prop("bench.chat", "").split(",")) {
            if (chat.isBlank()) continue;
            ChatModel model = OllamaChatModel.builder().ollamaApi(ollama)
                    .options(OllamaChatOptions.builder().model(chat.trim()).temperature(0.0).build()).build();
            summarise("ollama " + chat.trim(), runModel(model, corpus, categories, modelChars), false);
        }
        String google = prop("bench.google", "");
        if (!google.isBlank()) {
            String key = System.getenv("GOOGLE_API_KEY");
            if (key == null || key.isBlank()) {
                line("Google model " + google + " skipped: GOOGLE_API_KEY is not set");
            } else {
                ChatModel model = GoogleGenAiChatModel.builder().genAiClient(Client.builder().apiKey(key).build())
                        .options(GoogleGenAiChatOptions.builder().model(google).temperature(0.0).build()).build();
                summarise("google " + google, runModel(model, corpus, categories, modelChars), false);
            }
        }

        Path out = Path.of("target", "bench", "category-benchmark-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        flush();
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    private List<Outcome> runModel(ChatModel model, List<Sample> corpus, List<String> categories, int maxChars) {
        String system = InsightPrompts.system("BENCH", categories);
        List<Outcome> outcomes = new ArrayList<>();
        for (Sample sample : corpus) {
            String text = sample.text().length() > maxChars ? sample.text().substring(0, maxChars) : sample.text();
            long t0 = System.nanoTime();
            String predicted;
            try {
                String answer = ChatClient.builder(model).build().prompt()
                        .system(system)
                        .user(InsightPrompts.user(sample.fileName(), null, text))
                        .options(ChatOptions.builder().temperature(0.0).maxTokens(MAX_ANSWER_TOKENS))
                        .call().content();
                predicted = InsightResult.parse(answer, categories).category();
            } catch (Exception e) {
                predicted = "error";
                System.out.println("  ! " + sample.fileName() + ": " + e.getClass().getSimpleName() + " " + firstLine(e.getMessage()));
            }
            outcomes.add(new Outcome(sample, predicted, 1, 0, System.nanoTime() - t0));
            System.out.print(".");
        }
        System.out.println();
        return outcomes;
    }

    private void summarise(String title, List<Outcome> outcomes, boolean prototype) {
        long correct = outcomes.stream().filter(Outcome::correct).count();
        line("## " + title);
        line("");
        line(String.format(Locale.ROOT, "- accuracy **%.1f%%** (%d/%d), latency mean %s, p95 %s, max %s",
                100.0 * correct / outcomes.size(), correct, outcomes.size(),
                millis(outcomes.stream().mapToLong(Outcome::nanos).average().orElse(0)),
                millis(percentile(outcomes, 0.95)), millis(outcomes.stream().mapToLong(Outcome::nanos).max().orElse(0))));
        Map<String, long[]> recall = new TreeMap<>();
        for (Outcome o : outcomes) {
            long[] counts = recall.computeIfAbsent(o.sample().category(), k -> new long[2]);
            counts[1]++;
            if (o.correct()) counts[0]++;
        }
        line("- per label: " + recall.entrySet().stream()
                .map(e -> e.getKey() + " " + e.getValue()[0] + "/" + e.getValue()[1])
                .reduce((a, b) -> a + ", " + b).orElse(""));
        Map<String, Long> confusions = new LinkedHashMap<>();
        outcomes.stream().filter(o -> !o.correct())
                .forEach(o -> confusions.merge(o.sample().category() + " → " + o.predicted(), 1L, Long::sum));
        if (!confusions.isEmpty()) {
            line("- confusions: " + confusions.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10)
                    .map(e -> e.getKey() + " ×" + e.getValue()).reduce((a, b) -> a + ", " + b).orElse(""));
        }
        if (prototype) {
            // What auto mode would do: keep the verdicts at or above the threshold, ask the model for the rest
            StringBuilder curve = new StringBuilder();
            for (double threshold : new double[] {0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9}) {
                List<Outcome> kept = outcomes.stream().filter(o -> o.confidence() >= threshold).toList();
                long keptCorrect = kept.stream().filter(Outcome::correct).count();
                curve.append(String.format(Locale.ROOT, "%.1f: %d%% kept at %s | ", threshold,
                        Math.round(100.0 * kept.size() / outcomes.size()),
                        kept.isEmpty() ? "n/a" : Math.round(100.0 * keptCorrect / kept.size()) + "%"));
            }
            line("- auto mode (min-confidence → share decided locally, accuracy of those): " + curve);
            line(String.format(Locale.ROOT, "- best similarity, mean: correct %.3f, wrong %.3f (a min-similarity floor lives between them if anywhere)",
                    mean(outcomes, true), mean(outcomes, false)));
        }
        List<Outcome> wrong = outcomes.stream().filter(o -> !o.correct()).limit(15).toList();
        if (!wrong.isEmpty()) {
            line("- misses: " + wrong.stream()
                    .map(o -> o.sample().fileName() + " (" + o.sample().category() + " → " + o.predicted()
                            + (prototype ? String.format(Locale.ROOT, " @%.2f", o.confidence()) : "") + ")")
                    .reduce((a, b) -> a + "; " + b).orElse(""));
        }
        line("");
        flush();
    }

    /** Print what the report gained since the last flush: a section is readable the moment it is done. */
    private void flush() {
        System.out.print(report.substring(printed));
        printed = report.length();
    }

    private static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    private static double mean(List<Outcome> outcomes, boolean correct) {
        return outcomes.stream().filter(o -> o.correct() == correct).mapToDouble(Outcome::bestSimilarity).average().orElse(Double.NaN);
    }

    private static long percentile(List<Outcome> outcomes, double p) {
        List<Long> sorted = outcomes.stream().map(Outcome::nanos).sorted().toList();
        if (sorted.isEmpty()) return 0;
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(p * sorted.size())));
    }

    private static String millis(double nanos) {
        return String.format(Locale.ROOT, "%.0f ms", nanos / 1_000_000);
    }

    private static List<Sample> load(Path root, int limit) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("bench.dir is not a directory: " + root);
        }
        Tika tika = new Tika();
        tika.setMaxStringLength(20_000);
        List<Sample> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String category = dir.getFileName().toString().trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
                if (category.startsWith(".")) continue;
                int taken = 0;
                try (Stream<Path> files = Files.list(dir)) {
                    for (Path file : files.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                        if (file.getFileName().toString().startsWith(".")) continue;
                        if (limit > 0 && taken >= limit) break;
                        String text;
                        try {
                            text = tika.parseToString(file);
                        } catch (Exception e) {
                            System.out.println("  ! cannot read " + file + ": " + firstLine(e.getMessage()));
                            continue;
                        }
                        if (text == null || text.isBlank()) {
                            System.out.println("  ! no text in " + file);
                            continue;
                        }
                        out.add(new Sample(category, file.getFileName().toString(), text));
                        taken++;
                    }
                }
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no readable document under " + root + " (expected <category>/<files>)");
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
}
