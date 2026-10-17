package org.openfilz.dms.utils;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import java.util.List;
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
    public DatabaseClient.GenericExecuteSpec bindLikeCriteria(String criteria, String value, DatabaseClient.GenericExecuteSpec query) {
        return query.bind(criteria, "%" + value.toUpperCase() + "%");
    }

    /** Bind name of the exact (containment) part of a metadata filter. */
    public static final String METADATA_CRITERIA = "criteria";
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
                query = query.bind(METADATA_CRITERIA, objectMapper.writeValueAsString(criteria.exact()));
            } catch (JacksonException e) {
                throw new RuntimeException(e);
            }
        }
        List<MetadataCriteria.Pattern> patterns = criteria.patterns();
        if (!patterns.isEmpty()) {
            query = query.bind(METADATA_PATTERN_KEYS, patterns.stream().map(MetadataCriteria.Pattern::key).toArray(String[]::new));
            for (int i = 0; i < patterns.size(); i++) {
                MetadataCriteria.Pattern pattern = patterns.get(i);
                if (!pattern.anyValue()) {
                    query = query.bind(METADATA_PATTERN_KEY + i, pattern.key())
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

    public void appendLikeCriteria(String prefix, String criteria, StringBuilder sql) {
        sql.append("UPPER(").append(prefix == null ? criteria : prefix + criteria).append(") LIKE :").append(criteria).append(SPACE);
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
     * Appends the predicate of a metadata filter (see {@link MetadataCriteria}), without a leading
     * {@code AND} / {@code WHERE}:
     * <pre>
     * col @> :criteria::jsonb                                   -- exact values (GIN-indexed)
     * AND col ?& :mkeys                                         -- keys of the patterns (GIN-indexed)
     * AND col ->> :mk_0 ILIKE :mv_0 ESCAPE '\'                -- one recheck per pattern
     * </pre>
     * Keys and patterns are bound, never concatenated. The key-existence test keeps the GIN index
     * in play, so the pattern is only evaluated on the documents carrying the key. A bare
     * wildcard needs no recheck. Bind with {@link #bindMetadataCriteria(Map, DatabaseClient.GenericExecuteSpec)}.
     */
    public void appendMetadataCriteria(String prefix, String column, Map<String, Object> metadata, StringBuilder sql) {
        MetadataCriteria criteria = MetadataCriteria.of(metadata);
        String col = prefix == null ? column : prefix + column;
        boolean and = false;
        if (criteria.hasExact()) {
            sql.append(col).append(" @> :").append(METADATA_CRITERIA).append("::jsonb ");
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
