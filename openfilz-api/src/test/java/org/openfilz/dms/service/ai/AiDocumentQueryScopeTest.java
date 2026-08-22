package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.PageCriteria;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.graphql.ListFolderCriteria;
import org.openfilz.dms.utils.SqlUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * The SQL scope of an AI document query.
 * <p>
 * {@link ListFolderCriteria} is written for a folder <em>listing</em>, where a null id means "the
 * root level". An AI search for {@code folder="all"} means the opposite — every document at every
 * depth — and reaches the criteria as "no id, recursive true", which it read as root-only. In
 * production that made a global search silently return root-level documents only, and the model
 * walked the tree folder by folder instead, one LLM call per folder, until the provider's
 * per-minute quota ran out.
 * <p>
 * These assert on the emitted SQL because the scope <em>is</em> the SQL: the parent-id predicate
 * (or its absence) is the whole behaviour under test.
 */
class AiDocumentQueryScopeTest {

    private static final String PREFIX = "d.";
    private static final UUID FOLDER = UUID.fromString("86849cc3-9885-4cbf-8a43-c0ef5544f6c2");

    // The ObjectMapper is only reached by metadata binding, which no scope test exercises.
    private final ListFolderCriteria criteria = new ListFolderCriteria(new SqlUtils(mock(ObjectMapper.class)));

    /** The request shape {@code DocumentAiTools#queryDocuments} builds, with the scope under test. */
    private static ListFolderRequest request(UUID folderId, Boolean recursive, String nameLike) {
        return new ListFolderRequest(folderId, DocumentType.FILE, null, null, nameLike, null, null,
                null, null, null, null, null, null, null, true,
                new PageCriteria("createdAt", SortOrder.DESC, 1, 50), recursive);
    }

    /** Mirrors {@code AiDocumentQueryService#applyFilter}. */
    private String sql(ListFolderRequest request) {
        StringBuilder query = new StringBuilder("select * from Documents d");
        criteria.checkFilter(request);
        if (request.id() == null && Boolean.TRUE.equals(request.recursive())) {
            criteria.appendAllFilterExceptParentId(PREFIX, query, request, false);
        } else {
            criteria.applyFilter(PREFIX, query, request);
        }
        return query.toString();
    }

    /** What the shared criteria alone produces — the pre-fix behaviour. */
    private String sqlFromSharedCriteriaAlone(ListFolderRequest request) {
        StringBuilder query = new StringBuilder("select * from Documents d");
        criteria.checkFilter(request);
        criteria.applyFilter(PREFIX, query, request);
        return query.toString();
    }

    @Test
    @DisplayName("folder='all' searches every folder at every depth, not just the root")
    void searchesTheWholeTree() {
        ListFolderRequest all = request(null, true, "pdf");

        String sql = sql(all);

        assertThat(sql).doesNotContain("parent_id");
        assertThat(sql).contains("d.type = :type", "UPPER(d.name) LIKE :name", "d.active = :active");
        // The remaining filters must still open the WHERE clause themselves.
        assertThat(sql).contains(" WHERE d.type").doesNotContain("WHERE AND");
        assertThat(sql.split(" WHERE ", -1)).hasSize(2);

        // The production symptom this fixes: the shared criteria alone scoped it to the root.
        assertThat(sqlFromSharedCriteriaAlone(all)).contains("d.parent_id is null");
    }

    @Test
    @DisplayName("folder='root' still means the root level only")
    void rootStaysRootOnly() {
        ListFolderRequest root = request(null, false, null);

        assertThat(sql(root)).contains("d.parent_id is null");
        assertThat(sql(root)).isEqualTo(sqlFromSharedCriteriaAlone(root));
    }

    @Test
    @DisplayName("a named folder still lists that folder's own contents")
    void namedFolderIsUnchanged() {
        ListFolderRequest named = request(FOLDER, false, null);

        assertThat(sql(named)).contains("d.parent_id = :parent_id");
        assertThat(sql(named)).isEqualTo(sqlFromSharedCriteriaAlone(named));
    }

    @Test
    @DisplayName("a folder searched recursively keeps the GraphQL contract's descendants CTE")
    void recursiveWithinAFolderIsUnchanged() {
        ListFolderRequest deep = request(FOLDER, true, null);

        assertThat(sql(deep)).contains("WITH RECURSIVE descendants");
        assertThat(sql(deep)).isEqualTo(sqlFromSharedCriteriaAlone(deep));
    }

    @Test
    @DisplayName("counting across all folders is scoped the same way as listing them")
    void countUsesTheSameScope() {
        // countOnly requests carry no PageCriteria.
        ListFolderRequest countAll = new ListFolderRequest(null, DocumentType.FILE, null, null, null, null, null,
                null, null, null, null, null, null, null, true, null, true);

        StringBuilder query = new StringBuilder("select count(*) from Documents d");
        criteria.appendAllFilterExceptParentId(PREFIX, query, countAll, false);

        assertThat(query.toString()).doesNotContain("parent_id");
        assertThat(query.toString()).contains("d.type = :type", "d.active = :active");
    }
}
