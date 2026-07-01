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

    public DatabaseClient.GenericExecuteSpec bindMetadata(Map<String, Object> metadata, DatabaseClient.GenericExecuteSpec query) {
        try {
            String criteriaJson = objectMapper.writeValueAsString(metadata);
            return query.bind("criteria", criteriaJson);
        } catch (JacksonException e) {
            throw new RuntimeException(e);
        }
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

    public void appendJsonEqualsCriteria(String prefix, String criteria, StringBuilder sql) {
        appendPrefix(prefix, sql).append(criteria).append(" @> :criteria::jsonb ");
    }


}
