package org.openfilz.dms.service.ai;

import io.r2dbc.spi.Readable;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.graphql.DocumentFields;
import org.openfilz.dms.repository.graphql.ListFolderCriteria;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Function;

import static org.openfilz.dms.entity.SqlColumnMapping.*;

/**
 * Document query service for AI tools. Reuses the same SQL building logic as the GraphQL
 * data fetchers ({@link ListFolderCriteria}) but without requiring a {@code DataFetchingEnvironment}.
 * Always selects all document columns (no field-level optimization).
 */
@Slf4j
@Service
@Lazy
public class AiDocumentQueryService {

    protected static final String PREFIX = "d.";
    protected static final String FROM_CLAUSE = " from Documents d";
    private static final String CASE_FOLDER = ", CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END as d_type";

    /** All document columns we always select for AI queries. */
    private static final List<String> ALL_FIELDS = List.of(
            ID, NAME, TYPE, CONTENT_TYPE, SIZE, PARENT_ID, CREATED_AT, UPDATED_AT, CREATED_BY, UPDATED_BY
    );

    private final DatabaseClient databaseClient;
    private final ListFolderCriteria criteria;
    private final DocumentMapper mapper;
    private final DocumentFields documentFields;

    public AiDocumentQueryService(
            DatabaseClient databaseClient,
            @Qualifier("defaultListFolderCriteria") ListFolderCriteria criteria,
            DocumentMapper mapper,
            DocumentFields documentFields) {
        this.databaseClient = databaseClient;
        this.criteria = criteria;
        this.mapper = mapper;
        this.documentFields = documentFields;
    }

    /**
     * Query documents using the same filtering/sorting/pagination as the GraphQL layer,
     * on behalf of the given user. The core FROM clause has no per-user joins (the core
     * model has no per-document permissions); extension layers override
     * {@link #appendFromClause(StringBuilder, String)} / {@link #bindUserContext} to
     * restrict results to the user's own and shared documents.
     */
    public List<FullDocumentInfo> query(ListFolderRequest request, String userEmail) {
        log.debug("[AI-QUERY] query: scope={}, nameLike={}, type={}, sort={}:{}, page={}/{}",
                scope(request), request.nameLike(), request.type(),
                request.pageInfo().sortBy(), request.pageInfo().sortOrder(),
                request.pageInfo().pageNumber(), request.pageInfo().pageSize());

        StringBuilder query = buildSelect(userEmail);
        applyFilter(query, request);
        applySort(query, request);
        appendOffsetLimit(query, request);

        log.debug("[AI-QUERY] SQL: {}", query);

        DatabaseClient.GenericExecuteSpec sql = criteria.bindCriteria(
                databaseClient.sql(query.toString()), request);
        sql = bindUserContext(sql, userEmail);

        var results = sql.map(mapAllFields()).all().collectList().block();
        log.debug("[AI-QUERY] Returned {} results", results != null ? results.size() : 0);
        return results;
    }

    /**
     * Count documents matching the filter (same filtering as query, no sort/pagination).
     */
    public long count(ListFolderRequest request, String userEmail) {
        log.debug("[AI-QUERY] count: scope={}, nameLike={}, type={}", scope(request), request.nameLike(), request.type());

        StringBuilder query = new StringBuilder("select count(*)");
        appendFromClause(query, userEmail);
        applyFilter(query, request);

        log.debug("[AI-QUERY] SQL: {}", query);

        DatabaseClient.GenericExecuteSpec sql = criteria.bindCriteria(
                databaseClient.sql(query.toString()), request);
        sql = bindUserContext(sql, userEmail);

        Long result = sql.map(row -> row.get(0, Long.class)).one().block();
        log.debug("[AI-QUERY] Count: {}", result);
        return result != null ? result : 0;
    }

    /**
     * Apply the request's filters, honouring a whole-tree search.
     * <p>
     * {@link ListFolderCriteria} is written for a folder <em>listing</em>, where a null id means
     * "the root level" and {@code recursive} only widens an id that was given. An AI search for
     * {@code folder="all"} means something else entirely — every document at every depth — and
     * arrives here as exactly that combination: no id, recursive true. Left to the shared
     * criteria it silently became {@code parent_id IS NULL}, so a global search only ever saw
     * root-level documents and the model had to walk the tree folder by folder, one LLM call per
     * folder. Here that combination emits no parent predicate at all.
     * <p>
     * Handled in this service rather than in the shared criteria on purpose: the GraphQL contract
     * documents {@code recursive} as "when true and id is set", and other callers rely on a null
     * id meaning the root level.
     */
    private void applyFilter(StringBuilder query, ListFolderRequest request) {
        criteria.checkFilter(request);
        if (searchesWholeTree(request)) {
            criteria.appendAllFilterExceptParentId(PREFIX, query, request, false);
        } else {
            criteria.applyFilter(PREFIX, query, request);
        }
    }

    /**
     * The folder scope, for logs. {@code folder=null} was ambiguous between "the root level" and
     * "everywhere" — the two the parent-id bug conflated — so it names them apart.
     */
    private static String scope(ListFolderRequest request) {
        if (searchesWholeTree(request)) return "all folders";
        if (request.id() == null) return "root level only";
        return (Boolean.TRUE.equals(request.recursive()) ? "folder+subfolders " : "folder ") + request.id();
    }

    /** Whether this request asks for every document at every depth, rather than one folder's contents. */
    private static boolean searchesWholeTree(ListFolderRequest request) {
        return request.id() == null && Boolean.TRUE.equals(request.recursive());
    }

    /**
     * Append the FROM clause (and any per-user access joins) for the given user.
     * Core default: a bare {@code from Documents d} — no per-document permissions.
     */
    protected void appendFromClause(StringBuilder query, String userEmail) {
        query.append(FROM_CLAUSE);
    }

    /**
     * Bind any per-user parameters the overridden FROM clause introduced.
     * Core default: nothing to bind.
     */
    protected DatabaseClient.GenericExecuteSpec bindUserContext(
            DatabaseClient.GenericExecuteSpec spec, String userEmail) {
        return spec;
    }

    private StringBuilder buildSelect(String userEmail) {
        StringBuilder sb = new StringBuilder("select ");
        sb.append(String.join(", ", ALL_FIELDS.stream().map(f -> PREFIX + f).toList()));
        sb.append(CASE_FOLDER);
        appendFromClause(sb, userEmail);
        return sb;
    }

    private void applySort(StringBuilder query, ListFolderRequest request) {
        // When searching across all folders or filtering by type, sort purely by the requested field
        // (no d_type prefix which always puts folders first)
        boolean pureSortByField = request.recursive() != null && request.recursive()
                || request.type() != null;

        if (pureSortByField && request.pageInfo().sortBy() != null) {
            String sqlCol = documentFields.getDocumentFieldSqlMap().get(request.pageInfo().sortBy());
            if (sqlCol != null) {
                query.append(" ORDER BY ").append(PREFIX).append(sqlCol);
                if (request.pageInfo().sortOrder() != null) {
                    query.append(" ").append(request.pageInfo().sortOrder());
                }
                return;
            }
        }

        // Default: folders first, then by requested field
        query.append(" ORDER BY d_type");
        if (request.pageInfo().sortBy() != null) {
            String sqlCol = documentFields.getDocumentFieldSqlMap().get(request.pageInfo().sortBy());
            if (sqlCol != null) {
                query.append(", ").append(PREFIX).append(sqlCol);
                if (request.pageInfo().sortOrder() != null) {
                    query.append(" ").append(request.pageInfo().sortOrder());
                }
            }
        }
    }

    private void appendOffsetLimit(StringBuilder query, ListFolderRequest request) {
        query.append(" LIMIT ").append(request.pageInfo().pageSize());
        query.append(" OFFSET ").append((request.pageInfo().pageNumber() - 1) * request.pageInfo().pageSize());
    }

    private Function<Readable, FullDocumentInfo> mapAllFields() {
        return row -> mapper.toFullDocumentInfo(buildDocumentFromRow(row));
    }

    private Document buildDocumentFromRow(Readable row) {
        return Document.builder()
                .id(row.get(ID, java.util.UUID.class))
                .name(row.get(NAME, String.class))
                .type(DocumentType.valueOf(row.get(TYPE, String.class)))
                .contentType(row.get(CONTENT_TYPE, String.class))
                .size(row.get(SIZE, Long.class))
                .parentId(row.get(PARENT_ID, java.util.UUID.class))
                .createdAt(row.get(CREATED_AT, java.time.OffsetDateTime.class))
                .updatedAt(row.get(UPDATED_AT, java.time.OffsetDateTime.class))
                .createdBy(row.get(CREATED_BY, String.class))
                .updatedBy(row.get(UPDATED_BY, String.class))
                .build();
    }
}
