package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.SPACE;

@Slf4j
@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListFolderDataFetcher extends AbstractListDataFetcher<FullDocumentInfo> {


    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("defaultListFolderCriteria") ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = listFolderCriteria;
    }

    @Override
    protected void initFromWhereClause() {
        fromClause = FROM_DOCUMENTS;
    }

    @Override
    public Flux<FullDocumentInfo> get(ListFolderRequest filter, DataFetchingEnvironment environment) {
        List<String> sqlFields = getSqlFields(environment);
        if(filter.pageInfo() == null || filter.pageInfo().pageSize() == null || filter.pageInfo().pageNumber() == null) {
            throw new IllegalArgumentException("Paging information must be provided");
        }
        StringBuilder query = getSelectRequest(sqlFields);
        criteria.checkFilter(filter);
        criteria.checkPageInfo(filter);
        applyFilter(query, filter);
        applySort(query, filter);
        appendOffsetLimit(query, filter);
        DatabaseClient.GenericExecuteSpec sqlQuery = prepareQuery(environment, filter, query);
        log.debug("GraphQL - SQL query : {}", query);
        return sqlQuery.map(mapFullDocumentInfo(sqlFields))
                .all();
    }

    protected StringBuilder getSelectRequest(List<String> sqlFields) {
        return toSelect(sqlFields).append(fromClause);
    }

    protected void applyFilter(StringBuilder query, ListFolderRequest filter) {
        criteria.applyFilter(prefix, query, filter);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query) {
        return criteria.bindCriteria(databaseClient.sql(query.toString()), filter);
    }

    public void applySort(StringBuilder query, ListFolderRequest request) {
        if(request.pageInfo().sortBy() != null) {
            appendSort(query, request);
        }
    }

    private void appendSort(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.ORDER_BY).append(getSortByField(request));
        if(request.pageInfo().sortOrder() != null) {
            query.append(SPACE).append(request.pageInfo().sortOrder());
        }
    }

    private String getSortByField(ListFolderRequest request) {
        String sortBy = DOCUMENT_FIELD_SQL_MAP.get(request.pageInfo().sortBy());
        return prefix == null ? sortBy : prefix + sortBy;
    }

    public void appendOffsetLimit(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.LIMIT).append(request.pageInfo().pageSize())
                .append(SqlUtils.OFFSET).append((request.pageInfo().pageNumber() - 1) * request.pageInfo().pageSize());
    }

}
