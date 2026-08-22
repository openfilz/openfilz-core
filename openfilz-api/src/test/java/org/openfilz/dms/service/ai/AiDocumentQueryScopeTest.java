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

import java.util.List;
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
 * <p>
 * The scope is decided inside {@link ListFolderCriteria}, never around it. An extension layer with
 * per-document access control writes its access predicate in the same method that writes the
 * parent one, so a caller that skipped the criteria to widen the scope would drop the access
 * restriction with it — see {@code CollaborationListFolderCriteriaTest} in openfilz-enterprise,
 * which asserts the enterprise predicate survives every scope including this one.
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

    /** Mirrors {@code AiDocumentQueryService#applyFilter} — straight through the criteria. */
    private String sql(ListFolderRequest request) {
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
    }

    @Test
    @DisplayName("folder='root' still means the root level only")
    void rootStaysRootOnly() {
        ListFolderRequest root = request(null, false, null);

        assertThat(sql(root)).contains("d.parent_id is null");
    }

    @Test
    @DisplayName("a named folder still lists that folder's own contents")
    void namedFolderIsUnchanged() {
        ListFolderRequest named = request(FOLDER, false, null);

        assertThat(sql(named)).contains("d.parent_id = :parent_id");
    }

    @Test
    @DisplayName("a folder searched recursively keeps the GraphQL contract's descendants CTE")
    void recursiveWithinAFolderIsUnchanged() {
        ListFolderRequest deep = request(FOLDER, true, null);

        assertThat(sql(deep)).contains("WITH RECURSIVE descendants");
    }

    /**
     * Stand-in for an extension layer with per-document access control, shaped exactly like
     * {@code CollaborationListFolderCriteria} in openfilz-enterprise: the access predicate is
     * written by the same method that writes the parent scope.
     */
    private static final class AccessControlledCriteria extends ListFolderCriteria {
        static final String ACCESS = "(o.owner_id = :usrId or ds.user_id = :usrId) ";

        AccessControlledCriteria(SqlUtils sqlUtils) { super(sqlUtils); }

        @Override
        public boolean appendParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
            if (request.id() != null) {
                query.append("d.parent_id = :parent_id and ").append(ACCESS);
            } else if (Boolean.TRUE.equals(request.recursive())) {
                query.append(ACCESS);
            } else {
                query.append("d.parent_id is null and ").append(ACCESS);
            }
            return true;
        }
    }

    @Test
    @DisplayName("an access-controlled layer keeps its predicate on every scope, the whole tree included")
    void accessPredicateIsNeverBypassed() {
        ListFolderCriteria secured = new AccessControlledCriteria(new SqlUtils(mock(ObjectMapper.class)));

        // The whole-tree scope is the dangerous one: it has no parent predicate to hide behind,
        // so an implementation that emitted nothing here would return every user's documents
        // straight into the AI's prompt.
        for (ListFolderRequest request : List.of(
                request(null, true, "pdf"),      // folder="all"
                request(null, false, null),      // root only
                request(FOLDER, false, null),    // one folder
                request(FOLDER, true, null))) {  // folder + subfolders
            StringBuilder query = new StringBuilder("select * from Documents d");
            secured.applyFilter(PREFIX, query, request);

            assertThat(query.toString())
                    .as("access predicate must survive every scope")
                    .contains(AccessControlledCriteria.ACCESS);
            assertThat(query.toString()).contains(" WHERE ").doesNotContain("WHERE AND");
        }
    }

    @Test
    @DisplayName("counting across all folders is scoped the same way as listing them")
    void countUsesTheSameScope() {
        // countOnly requests carry no PageCriteria.
        ListFolderRequest countAll = new ListFolderRequest(null, DocumentType.FILE, null, null, null, null, null,
                null, null, null, null, null, null, null, true, null, true);

        StringBuilder query = new StringBuilder("select count(*) from Documents d");
        criteria.applyFilter(PREFIX, query, countAll);

        assertThat(query.toString()).doesNotContain("parent_id");
        assertThat(query.toString()).contains("d.type = :type", "d.active = :active");
    }
}
