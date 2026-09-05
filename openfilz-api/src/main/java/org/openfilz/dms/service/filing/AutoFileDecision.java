package org.openfilz.dms.service.filing;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The pure decision logic of smart filing, kept free of I/O so it can be tested exhaustively:
 * the neighbour vote (stage 1) and the model-answer contract (stage 2).
 */
public final class AutoFileDecision {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private AutoFileDecision() {
    }

    /** A similar document, where it lives now, and its tier-2 category when known. */
    public record Neighbour(UUID documentId, UUID folderId, double similarity, String category) {
        public Neighbour(UUID documentId, UUID folderId, double similarity) {
            this(documentId, folderId, similarity, null);
        }
    }

    /** The winning folder of a vote and how convincing it was. */
    public record Vote(UUID folderId, double share, double bestSimilarity, int documents, int totalDocuments) {
    }

    /**
     * Weight every neighbour's folder by similarity; the leading folder wins when it holds at
     * least {@code minShare} of the total weight and its best neighbour is at least
     * {@code minSimilarity} similar.
     */
    public static Optional<Vote> vote(Collection<Neighbour> neighbours, double minShare, double minSimilarity) {
        return vote(neighbours, null, minShare, minSimilarity, 0);
    }

    /**
     * The vote with the two guards of a mixed library: when the document's category is known, only
     * neighbours of the same category (or of an unknown one) vote — an invoice is never filed by
     * reports — and only neighbours at least {@code minRelativeSimilarity} as similar as the best
     * hit count, so the long tail of unrelated-but-not-dissimilar hits an embedding model returns
     * has no say. A folder used to win on headcount alone: whatever held the most embedded files
     * attracted everything, and each document it won made it stronger.
     */
    public static Optional<Vote> vote(Collection<Neighbour> neighbours, String documentCategory, double minShare,
                                      double minSimilarity, double minRelativeSimilarity) {
        if (neighbours == null || neighbours.isEmpty()) {
            return Optional.empty();
        }
        List<Neighbour> eligible = new ArrayList<>();
        double bestSimilarity = 0;
        for (Neighbour neighbour : neighbours) {
            if (neighbour == null || neighbour.similarity() <= 0) continue;
            if (documentCategory != null && neighbour.category() != null
                    && !sameCategory(documentCategory, neighbour.category())) continue;
            eligible.add(neighbour);
            bestSimilarity = Math.max(bestSimilarity, neighbour.similarity());
        }
        double floor = bestSimilarity * Math.max(0, Math.min(1, minRelativeSimilarity));
        Map<UUID, double[]> weights = new LinkedHashMap<>();   // [sum, best, count]
        double total = 0;
        for (Neighbour neighbour : eligible) {
            if (neighbour.similarity() < floor) continue;
            double[] w = weights.computeIfAbsent(neighbour.folderId(), k -> new double[3]);
            w[0] += neighbour.similarity();
            w[1] = Math.max(w[1], neighbour.similarity());
            w[2] += 1;
            total += neighbour.similarity();
        }
        if (total <= 0) {
            return Optional.empty();
        }
        Map.Entry<UUID, double[]> best = null;
        for (Map.Entry<UUID, double[]> entry : weights.entrySet()) {
            if (best == null || entry.getValue()[0] > best.getValue()[0]) {
                best = entry;
            }
        }
        double share = best.getValue()[0] / total;
        Vote result = new Vote(best.getKey(), share, best.getValue()[1], (int) best.getValue()[2], neighbours.size());
        return share >= minShare && best.getValue()[1] >= minSimilarity ? Optional.of(result) : Optional.empty();
    }

    private static boolean sameCategory(String a, String b) {
        return a.trim().equalsIgnoreCase(b.trim());
    }

    /**
     * Is a folder a home for this kind of document? Among its files with a known category, the
     * dominant one must be the document's and hold at least {@code minPurity} of them. A folder
     * without a categorised file, or a document without a category, passes: nothing says otherwise.
     * A grab-bag of invoices, reports and samples fails, and the model decides instead — it may
     * create the folder this kind deserves.
     */
    public static boolean coherent(Map<String, Integer> histogram, String documentCategory, double minPurity) {
        if (documentCategory == null || histogram == null || histogram.isEmpty()) {
            return true;
        }
        int total = 0;
        String dominant = null;
        int dominantCount = 0;
        for (Map.Entry<String, Integer> entry : histogram.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count <= 0) continue;
            total += count;
            if (count > dominantCount) {
                dominantCount = count;
                dominant = entry.getKey();
            }
        }
        if (total == 0 || dominant == null) {
            return true;
        }
        return sameCategory(dominant, documentCategory) && dominantCount >= minPurity * total;
    }

    /** What the model answers in stage 2. */
    public record ModelAnswer(String target, List<String> createFolders, double confidence, String reason) {

        /** Tolerates a Markdown fence and prose around the JSON object; rejects anything without a target. */
        public static ModelAnswer parse(String answer) {
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("empty answer");
            }
            String text = answer.trim();
            if (text.startsWith("```")) {
                int firstLine = text.indexOf('\n');
                String body = firstLine >= 0 ? text.substring(firstLine + 1) : "";
                int end = body.lastIndexOf("```");
                text = (end >= 0 ? body.substring(0, end) : body).trim();
            }
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start < 0 || end <= start) {
                throw new IllegalArgumentException("no JSON object in the answer");
            }
            JsonNode node;
            try {
                node = JSON.readTree(text.substring(start, end + 1));
            } catch (Exception e) {
                throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
            }
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("the answer is not a JSON object");
            }
            JsonNode targetNode = node.get("target");
            String target = targetNode == null || targetNode.isNull() ? null : targetNode.asString().trim();
            if (target == null) {
                throw new IllegalArgumentException("no target in the answer");
            }
            List<String> creates = new ArrayList<>();
            JsonNode createNode = node.get("createFolders");
            if (createNode != null && createNode.isArray()) {
                for (JsonNode item : createNode) {
                    if (item.isString() && !item.asString().isBlank()) creates.add(item.asString().trim());
                }
            }
            JsonNode confidenceNode = node.get("confidence");
            double confidence = 0;
            if (confidenceNode != null && !confidenceNode.isNull()) {
                try {
                    confidence = confidenceNode.isNumber() ? confidenceNode.asDouble()
                            : Double.parseDouble(confidenceNode.asString().trim());
                } catch (NumberFormatException ignored) {
                    confidence = 0;
                }
            }
            confidence = Math.max(0, Math.min(1, confidence));
            JsonNode reasonNode = node.get("reason");
            String reason = reasonNode == null || reasonNode.isNull() ? null : reasonNode.asString().trim();
            return new ModelAnswer(target, List.copyOf(creates), confidence, reason);
        }
    }

    /** Number of path segments of a relative folder path ("Finance/Invoices/2026" is 3). */
    public static int depthOf(String relativePath) {
        if (relativePath == null) return 0;
        int depth = 0;
        for (String segment : relativePath.replace('\\', '/').split("/")) {
            if (!segment.isBlank() && !".".equals(segment)) depth++;
        }
        return depth;
    }
}
