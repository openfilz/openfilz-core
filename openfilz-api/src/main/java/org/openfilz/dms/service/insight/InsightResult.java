package org.openfilz.dms.service.insight;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The tier-2 insight the model returns for a document, validated against the deployment's
 * category list. Parsing tolerates a Markdown code fence around the JSON; anything else is an
 * {@link IllegalArgumentException} the caller records as a FAILED enrichment.
 */
public record InsightResult(String category, String summary, List<String> keywords, String language,
                            Map<String, Object> entities) {

    public static final String OTHER = "other";
    static final int MAX_SUMMARY = 600;
    static final int MAX_KEYWORDS = 12;
    static final int MAX_ENTITIES = 12;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * @param answer     the model's raw answer
     * @param categories the closed category list; an unknown category becomes {@value #OTHER}
     */
    public static InsightResult parse(String answer, List<String> categories) {
        if (answer == null || answer.isBlank()) {
            throw new IllegalArgumentException("empty answer");
        }
        String json = stripFence(answer.trim());
        int start = json.indexOf('{');
        int end = json.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no JSON object in the answer");
        }
        JsonNode node;
        try {
            node = JSON.readTree(json.substring(start, end + 1));
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid JSON: " + e.getMessage());
        }
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException("the answer is not a JSON object");
        }
        return new InsightResult(
                category(text(node, "category"), categories),
                truncate(text(node, "summary"), MAX_SUMMARY),
                keywords(node.get("keywords")),
                language(text(node, "language")),
                entities(node.get("entities")));
    }

    static String stripFence(String text) {
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            String body = firstLine >= 0 ? text.substring(firstLine + 1) : "";
            int end = body.lastIndexOf("```");
            return (end >= 0 ? body.substring(0, end) : body).trim();
        }
        return text;
    }

    /** A category of the closed list as stored (lower case, hyphens), {@value #OTHER} when the value names none. */
    public static String category(String raw, List<String> categories) {
        if (raw == null) {
            return OTHER;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
        if (categories != null) {
            for (String candidate : categories) {
                if (candidate != null && candidate.trim().toLowerCase(Locale.ROOT).equals(normalized)) {
                    return candidate.trim().toLowerCase(Locale.ROOT);
                }
            }
        }
        return OTHER;
    }

    private static List<String> keywords(JsonNode node) {
        List<String> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                String keyword = item.isString() ? item.asString().trim() : null;
                if (keyword != null && !keyword.isEmpty() && !out.contains(keyword)) {
                    out.add(truncate(keyword, 64));
                    if (out.size() >= MAX_KEYWORDS) break;
                }
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> entities(JsonNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (node != null && node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                JsonNode value = entry.getValue();
                if (value == null || value.isNull() || value.isContainer()) continue;
                String text = value.isString() ? value.asString().trim() : value.asString();
                if (text == null || text.isEmpty()) continue;
                out.put(truncate(entry.getKey().trim(), 64), truncate(text, 255));
                if (out.size() >= MAX_ENTITIES) break;
            }
        }
        return Map.copyOf(out);
    }

    private static String language(String raw) {
        if (raw == null) return null;
        String primary = raw.trim().split("[-_]")[0].toLowerCase(Locale.ROOT);
        return primary.length() >= 2 && primary.length() <= 8 && primary.chars().allMatch(Character::isLetter) ? primary : null;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        String text = value.isString() ? value.asString() : value.toString();
        return text == null || text.isBlank() ? null : text.trim();
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
