package org.openfilz.dms.repository.graphql;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.utils.SqlUtils.isFirst;

@RequiredArgsConstructor
@Component
public class ListFolderCriteria {

    private final SqlUtils sqlUtils;

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
                query = sqlUtils.bindCriteria(CREATED_AT_FROM, SqlUtils.stringToDate(filter.createdAtAfter()), query);
                query = sqlUtils.bindCriteria(CREATED_AT_TO, SqlUtils.stringToDate(filter.createdAtBefore()), query);
            } else {
                query = sqlUtils.bindCriteria(CREATED_AT, SqlUtils.stringToDate(filter.createdAtBefore()), query);
            }
        } else if(filter.createdAtAfter() != null) {
            query = sqlUtils.bindCriteria(CREATED_AT, SqlUtils.stringToDate(filter.createdAtAfter()), query);
        }
        if(filter.updatedAtBefore() != null) {
            if(filter.updatedAtAfter() != null) {
                query = sqlUtils.bindCriteria(UPDATED_AT_FROM, SqlUtils.stringToDate(filter.updatedAtAfter()), query);
                query = sqlUtils.bindCriteria(UPDATED_AT_TO, SqlUtils.stringToDate(filter.updatedAtBefore()), query);
            } else {
                query = sqlUtils.bindCriteria(UPDATED_AT, SqlUtils.stringToDate(filter.updatedAtBefore()), query);
            }
        } else if(filter.updatedAtAfter() != null) {
            query = sqlUtils.bindCriteria(UPDATED_AT, SqlUtils.stringToDate(filter.updatedAtAfter()), query);
        }
        if(filter.createdBy() != null) {
            query = sqlUtils.bindCriteria(CREATED_BY, filter.createdBy(), query);
        }
        if(filter.updatedBy() != null) {
            query = sqlUtils.bindCriteria(UPDATED_BY, filter.updatedBy(), query);
        }
        return query;
    }

    public void checkFilter(ListFolderRequest filter) {
        if(filter.name() != null && filter.nameLike() != null) {
            throw new IllegalArgumentException("name and nameLike cannot be used simultaneously : choose name or nameLike in your filter");
        }
    }

    public void applyFilter(String prefix, StringBuilder query, ListFolderRequest request) {
        boolean first = true;
        if(request.id() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, PARENT_ID, query);
        } else {
            first = isFirst(first, query);
            sqlUtils.appendIsNullCriteria(prefix, PARENT_ID, query);
        }
        if(request.type() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, TYPE, query);
        }
        if(request.contentType() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, CONTENT_TYPE, query);
        }
        if(request.name() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, NAME, query);
        }
        if(request.nameLike() != null) {
            first = isFirst(first, query);
            sqlUtils.appendLikeCriteria(prefix, NAME, query);
        }
        if(request.metadata() != null && !request.metadata().isEmpty()) {
            first = isFirst(first, query);
            sqlUtils.appendJsonEqualsCriteria(prefix, METADATA, query);
        }
        if(request.size() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, SIZE, query);
        }
        if(request.createdBy() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, CREATED_BY, query);
        }
        if(request.updatedBy() != null) {
            first = isFirst(first, query);
            sqlUtils.appendEqualsCriteria(prefix, UPDATED_BY, query);
        }
        if(request.createdAtBefore() != null) {
            first = isFirst(first, query);
            if(request.createdAtAfter() != null) {
                sqlUtils.appendBetweenCriteria(prefix, CREATED_AT, query);
            } else {
                sqlUtils.appendLessThanCriteria(prefix, CREATED_AT, query);
            }
        } else if(request.createdAtAfter() != null) {
            first = isFirst(first, query);
            sqlUtils.appendGreaterThanCriteria(prefix, CREATED_AT, query);
        }
        if(request.updatedAtBefore() != null) {
            first = isFirst(first, query);
            if(request.updatedAtAfter() != null) {
                sqlUtils.appendBetweenCriteria(prefix, UPDATED_AT, query);
            } else {
                sqlUtils.appendLessThanCriteria(prefix, UPDATED_AT, query);
            }
        } else if(request.updatedAtAfter() != null) {
            first = isFirst(first, query);
            sqlUtils.appendGreaterThanCriteria(prefix, UPDATED_AT, query);
        }
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
