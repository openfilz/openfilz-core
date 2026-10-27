package org.openfilz.dms.utils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A metadata filter split into what the database answers with JSON containment and what it
 * answers with a pattern match.
 * <p>
 * A criterion value is a <b>pattern</b> when it is a string containing {@code %} or {@code *}:
 * {@code %} / {@code *} = any sequence of characters, {@code ?} = exactly one character,
 * {@code _} and {@code \} are literal. Patterns match case-insensitively; a bare {@code %} (or
 * {@code *}) means "the key exists, whatever its value". Every other value is matched exactly
 * by JSON containment, numbers, booleans and nested objects included. Keys and string values
 * always match case-insensitively (see {@code SqlUtils.appendMetadataCriteria}).
 *
 * @param exact    the criteria matched by JSON containment (may be empty)
 * @param patterns the criteria matched by pattern, ordered by key so the SQL text and the bind
 *                 names are produced in the same order (may be empty)
 */
public record MetadataCriteria(Map<String, Object> exact, List<Pattern> patterns) {

    public static final int MAX_CRITERIA = 10;
    public static final int MAX_KEY_LENGTH = 100;
    public static final int MAX_PATTERN_LENGTH = 200;

    /**
     * One pattern criterion.
     *
     * @param key       the metadata key
     * @param like      the SQL {@code LIKE} pattern, escaped for {@code ESCAPE '\'}
     * @param anyValue  true for a bare wildcard: the key only has to exist
     */
    public record Pattern(String key, String like, boolean anyValue) {
    }

    /**
     * Splits and validates the criteria.
     *
     * @throws IllegalArgumentException when the criteria exceed the limits (more than
     *                                  {@value #MAX_CRITERIA} criteria, a blank key or one longer
     *                                  than {@value #MAX_KEY_LENGTH} characters, a pattern longer
     *                                  than {@value #MAX_PATTERN_LENGTH} characters)
     */
    public static MetadataCriteria of(Map<String, Object> criteria) {
        validate(criteria);
        Map<String, Object> exact = new LinkedHashMap<>();
        List<Pattern> patterns = new ArrayList<>();
        if (criteria != null) {
            criteria.forEach((key, value) -> {
                if (isPattern(value)) {
                    String like = toLikePattern((String) value);
                    patterns.add(new Pattern(key, like, like.chars().allMatch(c -> c == '%')));
                } else {
                    exact.put(key, value);
                }
            });
        }
        patterns.sort(Comparator.comparing(Pattern::key));
        return new MetadataCriteria(exact, List.copyOf(patterns));
    }

    /** Checks the limits without building anything; see {@link #of(Map)}. */
    public static void validate(Map<String, Object> criteria) {
        if (criteria == null) {
            return;
        }
        if (criteria.size() > MAX_CRITERIA) {
            throw new IllegalArgumentException("At most " + MAX_CRITERIA + " metadata criteria are allowed");
        }
        criteria.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw new IllegalArgumentException("A metadata criterion key cannot be blank");
            }
            if (key.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("A metadata criterion key cannot exceed " + MAX_KEY_LENGTH + " characters");
            }
            if (isPattern(value) && ((String) value).length() > MAX_PATTERN_LENGTH) {
                throw new IllegalArgumentException("A metadata pattern cannot exceed " + MAX_PATTERN_LENGTH + " characters");
            }
        });
    }

    /** Whether a criterion value is a pattern: a string holding {@code %} or {@code *}. */
    public static boolean isPattern(Object value) {
        return value instanceof String s && (s.indexOf('%') >= 0 || s.indexOf('*') >= 0);
    }

    /**
     * Translates a user pattern to an SQL {@code LIKE} pattern escaped with {@code \}:
     * {@code %}/{@code *} → {@code %}, {@code ?} → {@code _}, {@code _} and {@code \} escaped.
     * Runs of wildcards collapse into one {@code %}.
     */
    public static String toLikePattern(String pattern) {
        StringBuilder like = new StringBuilder(pattern.length() + 8);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '%', '*' -> {
                    // a literal '%' is never produced, so a trailing '%' is always a wildcard
                    if (like.isEmpty() || like.charAt(like.length() - 1) != '%') {
                        like.append('%');
                    }
                }
                case '?' -> like.append('_');
                case '_', '\\' -> like.append('\\').append(c);
                default -> like.append(c);
            }
        }
        return like.toString();
    }

    /**
     * Translates a user pattern to an OpenSearch {@code wildcard} pattern: {@code %}/{@code *} →
     * {@code *}, {@code ?} kept, {@code \} escaped ({@code _} has no meaning there). Runs of
     * wildcards collapse into one {@code *}, as in {@link #toLikePattern}: a needlessly long
     * {@code **…} run only makes the query's automaton bigger.
     */
    public static String toWildcardPattern(String pattern) {
        StringBuilder wildcard = new StringBuilder(pattern.length() + 4);
        for (int i = 0; i < pattern.length(); i++) {
            char c = pattern.charAt(i);
            switch (c) {
                case '%', '*' -> {
                    // a literal '*' is never produced, so a trailing '*' is always a wildcard
                    if (wildcard.isEmpty() || wildcard.charAt(wildcard.length() - 1) != '*') {
                        wildcard.append('*');
                    }
                }
                case '\\' -> wildcard.append("\\\\");
                default -> wildcard.append(c);
            }
        }
        return wildcard.toString();
    }

    public boolean hasExact() {
        return !exact.isEmpty();
    }

    public boolean isEmpty() {
        return exact.isEmpty() && patterns.isEmpty();
    }
}
