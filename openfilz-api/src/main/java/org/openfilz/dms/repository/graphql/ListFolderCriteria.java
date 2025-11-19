package org.openfilz.dms.repository.graphql;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.utils.SqlUtils.*;

@RequiredArgsConstructor
@Component("defaultListFolderCriteria")
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

    public boolean appendWhereParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        query.append(WHERE);
        return appendParentIdFilter(prefix, query, request);
    }

    public boolean appendParentIdFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        if(request.id() != null) {
            sqlUtils.appendEqualsCriteria(prefix, PARENT_ID, query);
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
