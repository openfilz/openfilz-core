package org.openfilz.dms.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class SqlUtils {

    public static final String FROM_DOCUMENTS = " from Documents";
    public static final String OFFSET = " OFFSET ";
    public static final String LIMIT = " LIMIT ";
    public static final String SELECT = "select ";
    public static final String COMMA = ", ";
    public static final String ORDER_BY = " ORDER BY ";
    public static final String AND = "AND ";
    public static final String WHERE = " WHERE ";
    public static final String SPACE = " ";
    public static final int MAX_PAGE_SIZE = 100;
    public static final String UNDERSCORE = "_";

    private final ObjectMapper objectMapper;

    public static boolean isFirst(boolean first, StringBuilder sql) {
        if(!first) {
            sql.append(AND);
        } else {
            sql.append(WHERE);
            first = false;
        }
        return first;
    }

    public DatabaseClient.GenericExecuteSpec bindCriteria(String criteria, Object value, DatabaseClient.GenericExecuteSpec query) {
        return query.bind(criteria, value);
    }
    /**
     * Binds the "contains" pattern of {@link #appendLikeCriteria(String, String, StringBuilder)}: the
     * value is matched literally, so a {@code _} or a {@code %} in a file name is not a wildcard.
     */
    public DatabaseClient.GenericExecuteSpec bindLikeCriteria(String criteria, String value, DatabaseClient.GenericExecuteSpec query) {
        return query.bind(criteria, "%" + escapeLike(value) + "%");
    }

    /** Escapes the {@code LIKE} wildcards ({@code %}, {@code _}) and the escape character ({@code \}) of a literal value. */
    public static String escapeLike(String value) {
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Bind name of the exact (containment) part of a metadata filter. */
    public static final String METADATA_CRITERIA = "criteria";
    /**
     * Bind-name prefix of the alternatives of a string criterion, suffixed with its index: one JSON
     * array each ({@code malt_0}), or one containment each in the OR-ed form ({@code malt_0_1}).
     */
    public static final String METADATA_ALTERNATIVES = "malt_";
    /**
     * From this many string criteria, each is written as OR-ed containments instead of
     * {@code @> ANY(ARRAY(SELECT …))}. The planner cannot see inside the array, so from 5 such
     * criteria it gives up the index for a sequential scan that grows with the library; with
     * every spelling a bound value it indexes the selective criteria and filters the others.
     * Below it the array form is kept: its recheck evaluates the column expression once, not once
     * per spelling, which halves the cost of a non-selective criterion (measured on 500k
     * documents: 10 criteria 230 ms → 3 ms; one broad criterion 55 ms array, 99 ms OR-ed).
     */
    static final int METADATA_OR_FORM_FROM = 5;
    /** Bind name of the keys a metadata pattern filter requires ({@code text[]}). */
    public static final String METADATA_PATTERN_KEYS = "mkeys";
    /** Bind-name prefix of the metadata pattern keys / values, suffixed with their index. */
    public static final String METADATA_PATTERN_KEY = "mk_";
    public static final String METADATA_PATTERN_VALUE = "mv_";

    /**
     * Binds the parameters of {@link #appendMetadataCriteria(String, String, Map, StringBuilder)}
     * for the same criteria.
     */
    public DatabaseClient.GenericExecuteSpec bindMetadataCriteria(Map<String, Object> metadata, DatabaseClient.GenericExecuteSpec query) {
        MetadataCriteria criteria = MetadataCriteria.of(metadata);
        if (criteria.hasExact()) {
            try {
                // lower-cased in SQL (see appendMetadataCriteria), like the column
                query = query.bind(METADATA_CRITERIA, objectMapper.writeValueAsString(criteria.exact()));
            } catch (JacksonException e) {
                throw new RuntimeException(e);
            }
        }
        List<MetadataCriteria.Alternative> alternatives = criteria.alternatives();
        boolean orForm = alternatives.size() >= METADATA_OR_FORM_FROM;
        for (int i = 0; i < alternatives.size(); i++) {
            List<Map<String, Object>> containments = alternatives.get(i).containments();
            try {
                // lower-cased in SQL like the exact part
                if (orForm) {
                    for (int j = 0; j < containments.size(); j++) {
                        query = query.bind(METADATA_ALTERNATIVES + i + UNDERSCORE + j, objectMapper.writeValueAsString(containments.get(j)));
                    }
                } else {
                    query = query.bind(METADATA_ALTERNATIVES + i, objectMapper.writeValueAsString(containments));
                }
            } catch (JacksonException e) {
                throw new RuntimeException(e);
            }
        }
        List<MetadataCriteria.Pattern> patterns = criteria.patterns();
        if (!patterns.isEmpty()) {
            query = query.bind(METADATA_PATTERN_KEYS, patterns.stream().map(p -> p.key().toLowerCase(Locale.ROOT)).toArray(String[]::new));
            for (int i = 0; i < patterns.size(); i++) {
                MetadataCriteria.Pattern pattern = patterns.get(i);
                if (!pattern.anyValue()) {
                    query = query.bind(METADATA_PATTERN_KEY + i, pattern.key().toLowerCase(Locale.ROOT))
                            .bind(METADATA_PATTERN_VALUE + i, pattern.like());
                }
            }
        }
        return query;
    }

    public void appendEqualsCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" = :").append(criteria).append(SPACE);
    }

    private StringBuilder appendPrefix(String prefix, StringBuilder sql) {
        if(prefix != null) {
            return sql.append(prefix);
        }
        return sql;
    }

    /**
     * Case-insensitive "contains" on a column: {@code col ILIKE :criteria ESCAPE '\' }. Bind with
     * {@link #bindLikeCriteria(String, String, DatabaseClient.GenericExecuteSpec)}.
     */
    public void appendLikeCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" ILIKE :").append(criteria).append(" ESCAPE '\\' ");
    }

    /**
     * Appends an OR-group of case-insensitive LIKE clauses on the same column, one per bound pattern:
     * {@code (LOWER(col) LIKE :criteria_0 OR LOWER(col) LIKE :criteria_1 ) }. A pattern with no
     * {@code %} wildcard behaves as an exact (case-insensitive) match. Bind the values with
     * {@link #bindLikeAnyCriteria(String, List, DatabaseClient.GenericExecuteSpec)}.
     */
    public void appendLikeAnyCriteria(String prefix, String criteria, int count, StringBuilder sql) {
        String column = prefix == null ? criteria : prefix + criteria;
        sql.append("(");
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                sql.append("OR ");
            }
            sql.append("LOWER(").append(column).append(") LIKE :").append(criteria).append(UNDERSCORE).append(i).append(SPACE);
        }
        sql.append(") ");
    }

    public DatabaseClient.GenericExecuteSpec bindLikeAnyCriteria(String criteria, List<String> patterns, DatabaseClient.GenericExecuteSpec query) {
        for (int i = 0; i < patterns.size(); i++) {
            query = query.bind(criteria + UNDERSCORE + i, patterns.get(i).toLowerCase());
        }
        return query;
    }

    public void appendLessThanCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" <= :").append(criteria).append(SPACE);
    }

    public void appendGreaterThanCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" >= :").append(criteria).append(SPACE);
    }

    public void appendBetweenCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" between :").append(criteria).append("_from and :").append(criteria).append("_to ");
    }

    public void appendIsNullCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" is null ");
    }

    /**
     * The expression every metadata filter reads: the column with its keys and string values
     * lower-cased, so that keys and values match case-insensitively. It is GIN-indexed
     * ({@code idx_documents_metadata_ci}, V1_19) — keep it textually identical to the index.
     */
    public static String caseInsensitiveMetadata(String column) {
        return "(lower(" + column + "::text)::jsonb)";
    }

    /**
     * Appends the predicate of a metadata filter (see {@link MetadataCriteria}), without a leading
     * {@code AND} / {@code WHERE}; keys and values match case-insensitively:
     * <pre>
     * (lower(col::text)::jsonb) @> lower(:criteria)::jsonb      -- typed values (GIN-indexed)
     * AND (lower(col::text)::jsonb) @> ANY(ARRAY(SELECT jsonb_array_elements(lower(:malt_0)::jsonb)))
     *                                                           -- one per string value (GIN-indexed)
     * AND (lower(col::text)::jsonb) ?& :mkeys                   -- keys of the patterns (GIN-indexed)
     * AND (lower(col::text)::jsonb) ->> :mk_0 ILIKE :mv_0 ESCAPE '\'   -- one recheck per pattern
     * </pre>
     * A string value is matched by any of its spellings ({@link MetadataCriteria.Alternative#containments()}):
     * {@code "true"} also finds {@code true} and {@code ["true"]}. They are bound as one JSON array
     * per criterion, so the lower-casing stays Postgres's own {@code lower()}, identical to the
     * index expression; the {@code ARRAY(SELECT …)} is evaluated once and the planner still uses
     * the GIN index, one search per spelling. From {@value #METADATA_OR_FORM_FROM} string criteria
     * each is written {@code (col @> lower(:malt_0_0)::jsonb OR col @> lower(:malt_0_1)::jsonb …)}
     * instead, so the planner keeps the index (see {@link #METADATA_OR_FORM_FROM}).
     * Keys and patterns are bound, never concatenated. The key-existence test keeps the GIN index
     * in play, so the pattern is only evaluated on the documents carrying the key. A bare
     * wildcard needs no recheck. Bind with {@link #bindMetadataCriteria(Map, DatabaseClient.GenericExecuteSpec)}.
     */
    public void appendMetadataCriteria(String prefix, String column, Map<String, Object> metadata, StringBuilder sql) {
        MetadataCriteria criteria = MetadataCriteria.of(metadata);
        String col = caseInsensitiveMetadata(prefix == null ? column : prefix + column);
        boolean and = false;
        if (criteria.hasExact()) {
            sql.append(col).append(" @> lower(:").append(METADATA_CRITERIA).append(")::jsonb ");
            and = true;
        }
        List<MetadataCriteria.Alternative> alternatives = criteria.alternatives();
        boolean orForm = alternatives.size() >= METADATA_OR_FORM_FROM;
        for (int i = 0; i < alternatives.size(); i++) {
            if (and) {
                sql.append(AND);
            }
            if (orForm) {
                int count = alternatives.get(i).containments().size();
                sql.append("(");
                for (int j = 0; j < count; j++) {
                    if (j > 0) {
                        sql.append("OR ");
                    }
                    sql.append(col).append(" @> lower(:").append(METADATA_ALTERNATIVES).append(i)
                            .append(UNDERSCORE).append(j).append(")::jsonb ");
                }
                sql.append(") ");
            } else {
                sql.append(col).append(" @> ANY(ARRAY(SELECT jsonb_array_elements(lower(:")
                        .append(METADATA_ALTERNATIVES).append(i).append(")::jsonb))) ");
            }
            and = true;
        }
        List<MetadataCriteria.Pattern> patterns = criteria.patterns();
        if (!patterns.isEmpty()) {
            if (and) {
                sql.append(AND);
            }
            sql.append(col).append(" ?& :").append(METADATA_PATTERN_KEYS).append(SPACE);
            for (int i = 0; i < patterns.size(); i++) {
                if (!patterns.get(i).anyValue()) {
                    sql.append(AND).append(col).append(" ->> :").append(METADATA_PATTERN_KEY).append(i)
                            .append(" ILIKE :").append(METADATA_PATTERN_VALUE).append(i).append(" ESCAPE '\\' ");
                }
            }
        }
    }

    /**
     * Restricts to the documents whose {@code ai_document_insights} row has {@code column} among
     * the bound keys: {@code <prefix>id IN (SELECT document_id FROM ai_document_insights WHERE
     * <column> = ANY(:<bind>)) }. Bind the keys as a {@code String[]} with
     * {@link #bindInsightCriteria(String, List, DatabaseClient.GenericExecuteSpec)}. A document
     * without an insight row never matches.
     */
    public void appendInsightCriteria(String prefix, String column, String bind, StringBuilder sql) {
        appendPrefix(prefix, sql).append("id IN (SELECT document_id FROM ai_document_insights WHERE ")
                .append(column).append(" = ANY(:").append(bind).append(")) ");
    }

    public DatabaseClient.GenericExecuteSpec bindInsightCriteria(String bind, List<String> keys, DatabaseClient.GenericExecuteSpec query) {
        return query.bind(bind, keys.toArray(new String[0]));
    }


}
