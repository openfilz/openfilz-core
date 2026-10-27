package org.openfilz.dms.utils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A metadata filter split into what the database answers with JSON containment and what it
 * answers with a pattern match.
 * <p>
 * A criterion value is a <b>pattern</b> when it is a string containing {@code %} or {@code *}:
 * {@code %} / {@code *} = any sequence of characters, {@code ?} = exactly one character,
 * {@code _} and {@code \} are literal. Patterns match case-insensitively; a bare {@code %} (or
 * {@code *}) means "the key exists, whatever its value".
 * <p>
 * Any other <b>string</b> value is matched by its {@linkplain Alternative alternatives}: clients
 * (the web app, GraphQL {@code FilterInput.value}, the AI tools) send strings, while machine
 * writers store typed JSON. So {@code "true"} / {@code "false"} / {@code "null"} and a JSON
 * number literal ({@code "42"}, {@code "-1.5"}, {@code "1e3"}) also match the boolean, null or
 * number they spell, and every string criterion also matches a list containing it
 * ({@code tags: ["urgent", "q4"]} found by {@code tags = q4}). A value that is already typed
 * (boolean, number, object, list, null) is matched exactly by JSON containment, as sent. Keys and
 * string values always match case-insensitively (see {@code SqlUtils.appendMetadataCriteria}).
 *
 * @param exact        the typed criteria, matched by one JSON containment (may be empty)
 * @param alternatives the string criteria, each matched by any of its spellings, ordered by key
 *                     so the SQL text and the bind names are produced in the same order (may be empty)
 * @param patterns     the criteria matched by pattern, ordered by key (may be empty)
 */
public record MetadataCriteria(Map<String, Object> exact, List<Alternative> alternatives, List<Pattern> patterns) {

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
     * One string criterion without wildcard, with the JSON values it may denote.
     *
     * @param key       the metadata key
     * @param spellings the JSON scalars that match: the string itself, then the boolean, null or
     *                  number it spells, if any
     */
    public record Alternative(String key, List<Object> spellings) {

        /**
         * The JSON objects any of which the metadata must contain: each spelling as the value
         * ({@code {"k": true}}) and as the only member of a list ({@code {"k": [true]}}), so a
         * list value containing it matches too.
         */
        public List<Map<String, Object>> containments() {
            List<Map<String, Object>> containments = new ArrayList<>(spellings.size() * 2);
            for (Object spelling : spellings) {
                containments.add(singleton(key, spelling));
                containments.add(singleton(key, singletonList(spelling)));
            }
            return containments;
        }

        // Map.of / List.of refuse null, and "null" spells one
        private static Map<String, Object> singleton(String key, Object value) {
            Map<String, Object> map = new LinkedHashMap<>(2);
            map.put(key, value);
            return map;
        }

        private static List<Object> singletonList(Object value) {
            List<Object> list = new ArrayList<>(1);
            list.add(value);
            return list;
        }
    }

    /**
     * A JSON number literal: no leading zero, no leading {@code +}, no trailing {@code .}. The
     * exponent is capped at 4 digits: {@link #MAX_NUMBER_SCALE} rejects anything larger anyway,
     * and {@code BigDecimal} throws on an exponent that overflows an {@code int} ({@code 1e2147483648}).
     */
    private static final java.util.regex.Pattern JSON_NUMBER =
            java.util.regex.Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?([eE][+-]?\\d{1,4})?");
    /** Beyond this scale a literal stays a string: Postgres's numeric would refuse it. */
    private static final int MAX_NUMBER_SCALE = 1000;
    private static final int MAX_NUMBER_LENGTH = 100;

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
        List<Alternative> alternatives = new ArrayList<>();
        List<Pattern> patterns = new ArrayList<>();
        if (criteria != null) {
            criteria.forEach((key, value) -> {
                if (isPattern(value)) {
                    String like = toLikePattern((String) value);
                    patterns.add(new Pattern(key, like, like.chars().allMatch(c -> c == '%')));
                } else if (value instanceof String s) {
                    alternatives.add(new Alternative(key, spellings(s)));
                } else {
                    exact.put(key, value);
                }
            });
        }
        alternatives.sort(Comparator.comparing(Alternative::key));
        patterns.sort(Comparator.comparing(Pattern::key));
        return new MetadataCriteria(exact, List.copyOf(alternatives), List.copyOf(patterns));
    }

    /**
     * The JSON scalars a string criterion may denote: the string itself, plus the boolean, null
     * or number it spells (see {@link #typedValue(String)}).
     */
    static List<Object> spellings(String value) {
        List<Object> spellings = new ArrayList<>(2);
        spellings.add(value);
        Object typed = typedValue(value);
        if (typed != value) {
            spellings.add(typed);
        }
        return Collections.unmodifiableList(spellings);
    }

    /**
     * The boolean, null or number a string spells, or the string itself when it spells none:
     * {@code true} / {@code false} / {@code null} in any case, or a JSON number literal
     * ({@code 42}, {@code -1.5}, {@code 1e3}; {@code 007}, {@code 1.}, {@code +1}, {@code 0x1},
     * {@code " 42"} stay strings). "null" gives {@code null}.
     */
    public static Object typedValue(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            case "null" -> null;
            default -> {
                BigDecimal number = toNumber(value);
                yield number == null ? value : number;
            }
        };
    }

    /** The number a JSON number literal spells, or {@code null} when the string is not one. */
    public static BigDecimal toNumber(String value) {
        if (value.isEmpty() || value.length() > MAX_NUMBER_LENGTH || !JSON_NUMBER.matcher(value).matches()) {
            return null;
        }
        BigDecimal number;
        try {
            number = new BigDecimal(value);
        } catch (NumberFormatException e) {
            // a client's search value must never fail the request: it is a plain string then
            return null;
        }
        return Math.abs(number.scale()) > MAX_NUMBER_SCALE ? null : number;
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
        return exact.isEmpty() && alternatives.isEmpty() && patterns.isEmpty();
    }
}
