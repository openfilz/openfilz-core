package org.openfilz.dms.bench;

import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.filing.AutoFileDecision;
import org.openfilz.dms.service.filing.AutoFileDecision.FolderFit;
import org.openfilz.dms.service.filing.AutoFileDecision.Neighbour;
import org.openfilz.dms.service.filing.AutoFileDecision.Vote;
import org.openfilz.dms.service.insight.PrototypeCategoryClassifier;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.ollama.OllamaEmbeddingModel;
import org.springframework.ai.ollama.api.OllamaApi;
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
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Compares the stage-1 filing strategies on a labelled corpus, offline: every document is held
 * out in turn, its neighbours are the other documents ranked by cosine similarity of their
 * embeddings, and each strategy says where it would file it — its own folder (correct), another
 * (wrong) or nowhere (abstain: the rule or the model decide). Two libraries are simulated from
 * the same corpus: the <b>pure</b> one, a folder per kind, and the <b>grab-bag</b> one, where two
 * kinds share one "Mixed" folder — there the right answer for their documents is to abstain
 * (no home exists yet), and filing into Mixed is the snowball this work set out to stop.
 * <p>
 * Corpus layout and settings as in {@link CategoryClassifierBenchmark} ({@code bench.dir},
 * {@code bench.ollama.url}, {@code bench.embedding}, {@code bench.max-chars}, {@code bench.limit});
 * {@code bench.mixed} names the two kinds to merge (default: the two largest),
 * {@code bench.top-k} the neighbours consulted (20).
 * <pre>
 * mvn -pl openfilz-api test -Dtest=FilingStrategyBenchmark -Dsurefire.failIfNoSpecifiedTests=false -Dbench.dir=/path/to/corpus
 * </pre>
 * The pure functions under test are the ones the service runs ({@link AutoFileDecision}); the
 * extra vector query a similarity guard costs in production is counted, not timed.
 */
@EnabledIfSystemProperty(named = "bench.dir", matches = ".+")
class FilingStrategyBenchmark {

    private record Doc(UUID id, String label, String fileName, String text, float[] vector, String predictedLabel) {
    }

    /** What a strategy answers for one held-out document. */
    private record Answer(String folder, int extraQueries) {
        static final Answer ABSTAIN = new Answer(null, 0);
    }

    private interface Strategy {
        Answer decide(Doc doc, List<Neighbour> neighbours, Library library);
    }

    /** A simulated library: which folder each document lies in, and the folders' members. */
    private record Library(String name, Map<UUID, String> folderOf, Map<String, List<Doc>> members, Map<String, UUID> folderIds,
                           Map<UUID, String> folderNames) {
    }

    private final StringBuilder report = new StringBuilder();
    private int printed;

    @Test
    void run() throws Exception {
        Path root = Path.of(prop("bench.dir", ""));
        int limit = Integer.parseInt(prop("bench.limit", "0"));
        int maxChars = Integer.parseInt(prop("bench.max-chars", "2000"));
        int topK = Integer.parseInt(prop("bench.top-k", "20"));
        AiProperties.AutoFile config = new AiProperties.AutoFile();

        JdkClientHttpRequestFactory http = new JdkClientHttpRequestFactory();
        http.setReadTimeout(Duration.ofMinutes(5));
        OllamaApi ollama = OllamaApi.builder().baseUrl(prop("bench.ollama.url", "http://localhost:11434"))
                .restClientBuilder(RestClient.builder().requestFactory(http)).build();
        String embeddingName = prop("bench.embedding", "nomic-embed-text");
        EmbeddingModel embeddingModel = OllamaEmbeddingModel.builder().ollamaApi(ollama)
                .options(OllamaEmbeddingOptions.builder().model(embeddingName).build()).build();
        List<String> categories = new AiProperties.Insights().getCategories();
        AiProperties.Insights.Classifier classifierConfig = new AiProperties.Insights.Classifier();
        classifierConfig.setMaxChars(maxChars);
        PrototypeCategoryClassifier classifier = new PrototypeCategoryClassifier(embeddingModel, embeddingName, categories, classifierConfig);

        // ── the corpus, embedded once (the document head, as the service embeds it for the vote) ──
        List<Doc> docs = new ArrayList<>();
        long embedNanos = 0;
        for (Map.Entry<String, List<Map.Entry<String, String>>> entry : load(root, limit).entrySet()) {
            for (Map.Entry<String, String> file : entry.getValue()) {
                String head = file.getValue().length() > maxChars ? file.getValue().substring(0, maxChars) : file.getValue();
                long t0 = System.nanoTime();
                float[] vector = embeddingModel.embed(head);
                embedNanos += System.nanoTime() - t0;
                String predicted = classifier.classify(null, file.getKey(), head).category();
                docs.add(new Doc(UUID.randomUUID(), entry.getKey(), file.getKey(), head, vector, predicted));
            }
        }
        Map<String, Long> perLabel = new TreeMap<>();
        docs.forEach(d -> perLabel.merge(d.label(), 1L, Long::sum));
        long predictedRight = docs.stream().filter(d -> d.label().equals(d.predictedLabel())).count();
        line("# Filing strategy benchmark");
        line("");
        line(String.format(Locale.ROOT, "Corpus: `%s` — %d documents, %d kinds %s; embedding %s, %.0f ms per document; "
                        + "prototype category right for %d/%d.", root, docs.size(), perLabel.size(), perLabel, embeddingName,
                embedNanos / 1e6 / Math.max(1, docs.size()), predictedRight, docs.size()));
        line("");

        // ── the two libraries ──
        List<String> largest = perLabel.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).map(Map.Entry::getKey).limit(2).toList();
        List<String> mixedKinds = List.of(prop("bench.mixed", String.join(",", largest)).split(","));
        Library pure = library("pure", docs, d -> d.label());
        Library grabBag = library("grab-bag (" + String.join(" + ", mixedKinds) + " share one folder)", docs,
                d -> mixedKinds.contains(d.label()) ? "Mixed" : d.label());

        // ── the strategies ──
        Map<String, Strategy> strategies = new LinkedHashMap<>();
        strategies.put("headcount vote (before)", (doc, neighbours, lib) ->
                answer(AutoFileDecision.vote(neighbours, null, config.getNeighbourMinShare(), config.getNeighbourMinSimilarity(), 0), lib, 0));
        strategies.put("vote + relative floor", (doc, neighbours, lib) ->
                answer(AutoFileDecision.vote(neighbours, null, config.getNeighbourMinShare(), config.getNeighbourMinSimilarity(),
                        config.getNeighbourMinRelativeSimilarity()), lib, 0));
        strategies.put("vote + guards, true category", (doc, neighbours, lib) ->
                guarded(doc, neighbours, lib, doc.label(), AiProperties.AutoFile.Coherence.CATEGORY, config));
        strategies.put("vote + guards, prototype category (default)", (doc, neighbours, lib) ->
                guarded(doc, withCategories(neighbours, lib, Doc::predictedLabel), lib, doc.predictedLabel(), AiProperties.AutoFile.Coherence.CATEGORY, config));
        strategies.put("vote + similarity coherence, no category", (doc, neighbours, lib) ->
                guarded(doc, neighbours.stream().map(n -> new Neighbour(n.documentId(), n.folderId(), n.similarity())).toList(), lib, null,
                        AiProperties.AutoFile.Coherence.SIMILARITY, config));
        strategies.put("vote + both guards, prototype category", (doc, neighbours, lib) ->
                guarded(doc, withCategories(neighbours, lib, Doc::predictedLabel), lib, doc.predictedLabel(), AiProperties.AutoFile.Coherence.BOTH, config));
        strategies.put("fit (purity × closeness), no category", (doc, neighbours, lib) -> fit(doc, neighbours, lib, config));

        for (Library library : List.of(pure, grabBag)) {
            line("## Library: " + library.name());
            line("");
            line("| strategy | correct | wrong | into a grab-bag | abstain (rule/model) | extra vector queries / decision |");
            line("|---|---|---|---|---|---|");
            for (Map.Entry<String, Strategy> strategy : strategies.entrySet()) {
                int correct = 0, wrong = 0, grab = 0, abstain = 0, queries = 0;
                for (Doc doc : docs) {
                    List<Neighbour> neighbours = neighbours(doc, docs, library, topK, config.getNeighbourMinSimilarity());
                    Answer answer = strategy.getValue().decide(doc, neighbours, library);
                    queries += answer.extraQueries();
                    String expected = library.folderOf().get(doc.id());
                    boolean inGrabBag = "Mixed".equals(expected);
                    if (answer.folder() == null) {
                        abstain++;
                    } else if ("Mixed".equals(answer.folder())) {
                        grab++;
                    } else if (answer.folder().equals(expected)) {
                        correct++;
                    } else {
                        wrong++;
                    }
                    if (inGrabBag && answer.folder() == null) {
                        // abstaining on a document whose only home is the grab-bag is the right call
                        abstain--;
                        correct++;
                    }
                }
                line(String.format(Locale.ROOT, "| %s | %d%% | %d%% | %d%% | %d%% | %.1f |", strategy.getKey(),
                        pct(correct, docs.size()), pct(wrong, docs.size()), pct(grab, docs.size()), pct(abstain, docs.size()),
                        (double) queries / docs.size()));
            }
            line("");
            line("In the grab-bag library, a document of a merged kind counts as correct when the strategy abstains "
                    + "(no home exists yet: the rule creates one) and as \"into a grab-bag\" when it files it into Mixed.");
            line("");
            flush();
        }

        Path out = Path.of("target", "bench", "filing-benchmark-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report, StandardCharsets.UTF_8);
        flush();
        System.out.println("Report written to " + out.toAbsolutePath());
    }

    // ── strategies ──────────────────────────────────────────────────────────

    private static Answer answer(Optional<Vote> vote, Library library, int queries) {
        return vote.map(v -> new Answer(library.folderNames().get(v.folderId()), queries)).orElse(new Answer(null, queries));
    }

    private static Answer guarded(Doc doc, List<Neighbour> neighbours, Library library, String category,
                                  AiProperties.AutoFile.Coherence coherence, AiProperties.AutoFile config) {
        Optional<Vote> vote = AutoFileDecision.vote(neighbours, category, config.getNeighbourMinShare(),
                config.getNeighbourMinSimilarity(), config.getNeighbourMinRelativeSimilarity());
        if (vote.isEmpty()) {
            return Answer.ABSTAIN;
        }
        String folder = library.folderNames().get(vote.get().folderId());
        int queries = 0;
        if (coherence != AiProperties.AutoFile.Coherence.SIMILARITY) {
            Map<String, Integer> histogram = new LinkedHashMap<>();
            for (Doc member : library.members().get(folder)) {
                if (member.id().equals(doc.id())) continue;
                String kind = category == null || category.equals(doc.label()) ? member.label() : member.predictedLabel();
                histogram.merge(kind, 1, Integer::sum);
            }
            if (!AutoFileDecision.coherent(histogram, category, config.getNeighbourMinFolderPurity())) {
                return Answer.ABSTAIN;
            }
        }
        if (coherence != AiProperties.AutoFile.Coherence.CATEGORY) {
            queries++;
            if (!AutoFileDecision.coherentBySimilarity(memberSimilarities(doc, library.members().get(folder)),
                    config.getFolderSimilarityGap(), config.getNeighbourMinFolderPurity(), config.getFolderMinMembers())) {
                return new Answer(null, queries);
            }
        }
        return new Answer(folder, queries);
    }

    private static Answer fit(Doc doc, List<Neighbour> neighbours, Library library, AiProperties.AutoFile config) {
        Map<UUID, Integer> votes = new LinkedHashMap<>();
        neighbours.forEach(n -> votes.merge(n.folderId(), 1, Integer::sum));
        Map<UUID, List<Double>> candidates = new LinkedHashMap<>();
        votes.entrySet().stream().sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed()).limit(5)
                .forEach(e -> candidates.put(e.getKey(), memberSimilarities(doc, library.members().get(library.folderNames().get(e.getKey())))));
        Optional<FolderFit> best = AutoFileDecision.fit(candidates, config.getFolderSimilarityGap(),
                config.getNeighbourMinFolderPurity(), config.getNeighbourMinSimilarity(), config.getFolderMinMembers());
        return best.map(f -> new Answer(library.folderNames().get(f.folderId()), candidates.size()))
                .orElse(new Answer(null, candidates.size()));
    }

    // ── the simulated library ───────────────────────────────────────────────

    private static Library library(String name, List<Doc> docs, Function<Doc, String> folderOf) {
        Map<UUID, String> byDoc = new LinkedHashMap<>();
        Map<String, List<Doc>> members = new LinkedHashMap<>();
        Map<String, UUID> ids = new LinkedHashMap<>();
        Map<UUID, String> names = new LinkedHashMap<>();
        for (Doc doc : docs) {
            String folder = folderOf.apply(doc);
            byDoc.put(doc.id(), folder);
            members.computeIfAbsent(folder, k -> new ArrayList<>()).add(doc);
            UUID id = ids.computeIfAbsent(folder, k -> UUID.randomUUID());
            names.put(id, folder);
        }
        return new Library(name, byDoc, members, ids, names);
    }

    /** The held-out document's nearest others, as the service sees them: folder, similarity, true category. */
    private static List<Neighbour> neighbours(Doc doc, List<Doc> docs, Library library, int topK, double minSimilarity) {
        List<Doc> others = docs.stream().filter(d -> !d.id().equals(doc.id()))
                .sorted(Comparator.comparingDouble((Doc d) -> cosine(doc.vector(), d.vector())).reversed())
                .limit(topK).toList();
        List<Neighbour> out = new ArrayList<>();
        for (Doc other : others) {
            String folder = library.folderOf().get(other.id());
            out.add(new Neighbour(other.id(), library.folderIds().get(folder), cosine(doc.vector(), other.vector()), other.label()));
        }
        return out;
    }

    private static List<Neighbour> withCategories(List<Neighbour> neighbours, Library library, Function<Doc, String> category) {
        Map<UUID, Doc> byId = new LinkedHashMap<>();
        library.members().values().forEach(list -> list.forEach(d -> byId.put(d.id(), d)));
        return neighbours.stream().map(n -> new Neighbour(n.documentId(), n.folderId(), n.similarity(), category.apply(byId.get(n.documentId())))).toList();
    }

    private static List<Double> memberSimilarities(Doc doc, List<Doc> members) {
        List<Double> out = new ArrayList<>();
        for (Doc member : members) {
            if (!member.id().equals(doc.id())) out.add(cosine(doc.vector(), member.vector()));
        }
        return out;
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

    // ── plumbing ────────────────────────────────────────────────────────────

    private static int pct(int count, int total) {
        return (int) Math.round(100.0 * count / Math.max(1, total));
    }

    /** label → (file name, text). */
    private static Map<String, List<Map.Entry<String, String>>> load(Path root, int limit) throws IOException {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("bench.dir is not a directory: " + root);
        }
        Tika tika = new Tika();
        tika.setMaxStringLength(20_000);
        Map<String, List<Map.Entry<String, String>>> out = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(root)) {
            for (Path dir : dirs.filter(Files::isDirectory).sorted().toList()) {
                String label = dir.getFileName().toString().trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
                if (label.startsWith(".")) continue;
                List<Map.Entry<String, String>> files = new ArrayList<>();
                try (Stream<Path> list = Files.list(dir)) {
                    for (Path file : list.filter(Files::isRegularFile).sorted(Comparator.comparing(Path::toString)).toList()) {
                        if (file.getFileName().toString().startsWith(".")) continue;
                        if (limit > 0 && files.size() >= limit) break;
                        try {
                            String text = tika.parseToString(file);
                            if (text != null && !text.isBlank()) files.add(Map.entry(file.getFileName().toString(), text));
                        } catch (Exception e) {
                            System.out.println("  ! cannot read " + file + ": " + e.getMessage());
                        }
                    }
                }
                if (!files.isEmpty()) out.put(label, files);
            }
        }
        if (out.isEmpty()) {
            throw new IllegalArgumentException("no readable document under " + root + " (expected <category>/<files>)");
        }
        return out;
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
