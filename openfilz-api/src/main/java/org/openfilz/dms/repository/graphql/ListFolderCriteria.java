package org.openfilz.dms.repository.graphql;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.utils.SqlUtils.AND;
import static org.openfilz.dms.utils.SqlUtils.WHERE;

@RequiredArgsConstructor
@Component("defaultListFolderCriteria")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListFolderCriteria {

    public static final String NON_FAVORITE_CLAUSE = "uf.doc_id IS NULL ";
    protected final SqlUtils sqlUtils;

    public DatabaseClient.GenericExecuteSpec bindCriteria(DatabaseClient.GenericExecuteSpec query, ListFolderRequest filter) {
        if(filter.id() != null) {
            query = sqlUtils.bindCriteria(PARENT_ID, filter.id(), query);
        }
        if(filter.name() != null) {
            query = sqlUtils.bindCriteria(NAME, filter.name(), query);
        }
        if(filter.nameLike() != null) {
            query = sqlUtils.bindLikeCriteria(NAME, filter.nameLike(), query);
        }
        if(filter.type() != null) {
            query = sqlUtils.bindCriteria(TYPE, filter.type().toString(), query);
        }
        if(filter.contentType() != null) {
            query = sqlUtils.bindCriteria(CONTENT_TYPE, filter.contentType(), query);
        }
        if(filter.contentTypes() != null && !filter.contentTypes().isEmpty()) {
            query = sqlUtils.bindLikeAnyCriteria(CONTENT_TYPE, filter.contentTypes(), query);
        }
        if(filter.metadata() != null) {
            query = sqlUtils.bindMetadata(filter.metadata(), query);
        }
        if(filter.size() != null) {
            query = sqlUtils.bindCriteria(SIZE, filter.size(), query);
        }
        if(filter.createdAtBefore() != null) {
            if(filter.createdAtAfter() != null) {
                query = sqlUtils.bindCriteria(CREATED_AT_FROM, filter.createdAtAfter(), query);
                query = sqlUtils.bindCriteria(CREATED_AT_TO, filter.createdAtBefore(), query);
            } else {
                query = sqlUtils.bindCriteria(CREATED_AT, filter.createdAtBefore(), query);
            }
        } else if(filter.createdAtAfter() != null) {
            query = sqlUtils.bindCriteria(CREATED_AT, filter.createdAtAfter(), query);
        }
        if(filter.updatedAtBefore() != null) {
            if(filter.updatedAtAfter() != null) {
                query = sqlUtils.bindCriteria(UPDATED_AT_FROM, filter.updatedAtAfter(), query);
                query = sqlUtils.bindCriteria(UPDATED_AT_TO, filter.updatedAtBefore(), query);
            } else {
                query = sqlUtils.bindCriteria(UPDATED_AT, filter.updatedAtBefore(), query);
            }
        } else if(filter.updatedAtAfter() != null) {
            query = sqlUtils.bindCriteria(UPDATED_AT, filter.updatedAtAfter(), query);
        }
        if(filter.createdBy() != null) {
            query = sqlUtils.bindCriteria(CREATED_BY, filter.createdBy(), query);
        }
        if(filter.updatedBy() != null) {
            query = sqlUtils.bindCriteria(UPDATED_BY, filter.updatedBy(), query);
        }
        if(filter.active() != null) {
            query = sqlUtils.bindCriteria(ACTIVE, filter.active(), query);
        }
        return query;
    }

    public void checkFilter(ListFolderRequest filter) {
        if(filter.name() != null && filter.nameLike() != null) {
            throw new IllegalArgumentException("name and nameLike cannot be used simultaneously : choose name or nameLike in your filter");
        }
    }

    public void applyFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        boolean appendAnd = appendWhereParentIdFilter(prefix, query, request);
        appendAllFilterExceptParentId(prefix, query, request, appendAnd);
    }

    public void appendAllFilterExceptParentId(String prefix, StringBuilder query, ListFolderRequest request, boolean appendAnd) {
        if(request.type() != null) {
            sqlUtils.appendEqualsCriteria(prefix, TYPE, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.contentType() != null) {
            sqlUtils.appendEqualsCriteria(prefix, CONTENT_TYPE, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.contentTypes() != null && !request.contentTypes().isEmpty()) {
            sqlUtils.appendLikeAnyCriteria(prefix, CONTENT_TYPE, request.contentTypes().size(), appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.name() != null) {
            sqlUtils.appendEqualsCriteria(prefix, NAME, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.nameLike() != null) {
            sqlUtils.appendLikeCriteria(prefix, NAME, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.metadata() != null && !request.metadata().isEmpty()) {
            sqlUtils.appendJsonEqualsCriteria(prefix, METADATA, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.size() != null) {
            sqlUtils.appendEqualsCriteria(prefix, SIZE, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.createdBy() != null) {
            sqlUtils.appendEqualsCriteria(prefix, CREATED_BY, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.updatedBy() != null) {
            sqlUtils.appendEqualsCriteria(prefix, UPDATED_BY, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.createdAtBefore() != null) {
            if(request.createdAtAfter() != null) {
                sqlUtils.appendBetweenCriteria(prefix, CREATED_AT, appendAnd(query, appendAnd));
                appendAnd = true;
            } else {
                sqlUtils.appendLessThanCriteria(prefix, CREATED_AT, appendAnd(query, appendAnd));
                appendAnd = true;
            }
        } else if(request.createdAtAfter() != null) {
            sqlUtils.appendGreaterThanCriteria(prefix, CREATED_AT, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.updatedAtBefore() != null) {
            if(request.updatedAtAfter() != null) {
                sqlUtils.appendBetweenCriteria(prefix, UPDATED_AT, appendAnd(query, appendAnd));
            } else {
                sqlUtils.appendLessThanCriteria(prefix, UPDATED_AT, appendAnd(query, appendAnd));
            }
            appendAnd = true;
        } else if(request.updatedAtAfter() != null) {
            sqlUtils.appendGreaterThanCriteria(prefix, UPDATED_AT, appendAnd(query, appendAnd));
            appendAnd = true;
        }
        if(request.active() != null) {
            sqlUtils.appendEqualsCriteria(prefix, ACTIVE, appendAnd(query, appendAnd));
        }
        if(request.favorite() != null && !request.favorite()) {
            if(!query.toString().contains(WHERE)) {
                query.append(WHERE);
            } else  {
                query.append(AND);
            }
            query.append(NON_FAVORITE_CLAUSE);
        }
    }

    private StringBuilder appendAnd(StringBuilder query, boolean appendAnd) {
        if(appendAnd) {
            return query.append(AND);
        } else {
            return query.append(WHERE);
        }
    }

    /**
     * Emit the folder-scope predicate, opening the WHERE clause only if there is one to emit.
     * <p>
     * The predicate is built aside first because {@link #appendParentIdFilter} may legitimately
     * produce nothing — a search across every folder has no parent to scope by. Appending WHERE
     * up front would then leave a dangling {@code WHERE} for the next filter to trip over.
     *
     * @return whether a predicate was written, i.e. whether the next filter must join with AND
     */
    public boolean appendWhereParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        StringBuilder scope = new StringBuilder();
        if(!appendParentIdFilter(prefix, scope, request)) {
            return false;
        }
        query.append(WHERE).append(scope);
        return true;
    }

    /**
     * The folder scope of a listing, and — in layers that have per-document access control — the
     * access predicate that goes with it.
     * <p>
     * <b>Extension layers restricting documents per user must put that restriction here</b>, in
     * every branch including the whole-tree one. This is the single method every caller routes
     * through for scoping, which is why the enterprise overrides live here rather than in a
     * separate hook that a new branch could forget to call.
     *
     * @return whether anything was written; false means "no scope predicate", not "no rows"
     */
    public boolean appendParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        if(request.id() != null) {
            if(Boolean.TRUE.equals(request.recursive())) {
                if(prefix != null) {
                    query.append(prefix);
                }
                query.append(PARENT_ID).append(" IN (")
                        .append("WITH RECURSIVE descendants AS (")
                        .append("SELECT id FROM documents WHERE id = :").append(PARENT_ID)
                        .append(" UNION ALL ")
                        .append("SELECT d2.id FROM documents d2 ")
                        .append("INNER JOIN descendants p ON d2.parent_id = p.id ")
                        .append("WHERE d2.type = 'FOLDER'")
                        .append(") SELECT id FROM descendants) ");
            } else {
                sqlUtils.appendEqualsCriteria(prefix, PARENT_ID, query);
            }
        } else if(Boolean.TRUE.equals(request.recursive())) {
            // Every folder at every depth: no parent scope at all. Core has no per-document
            // access control, so there is nothing else to constrain it by either — an extension
            // layer that does MUST override this branch, or its documents go unfiltered.
            return false;
        } else {
            sqlUtils.appendIsNullCriteria(prefix, PARENT_ID, query);
        }
        return true;
    }

    public void checkPageInfo(ListFolderRequest request) {
        if(request.pageInfo() == null) {
            throw new IllegalArgumentException("page info is required");
        }
        if(request.pageInfo().pageNumber() == null || request.pageInfo().pageNumber() < 1 ) {
            throw new IllegalArgumentException("pageInfo.pageNumber must be greater than 1");
        }
        if(request.pageInfo().pageSize() == null || request.pageInfo().pageSize() > SqlUtils.MAX_PAGE_SIZE) {
            throw new IllegalArgumentException("pageInfo.pageSize must be not null & less than " + SqlUtils.MAX_PAGE_SIZE);
        }
    }

}
