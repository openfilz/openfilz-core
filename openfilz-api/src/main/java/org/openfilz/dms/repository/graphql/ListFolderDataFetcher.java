package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import io.r2dbc.spi.Readable;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.GraphQlQueryConfig;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.Function;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.SPACE;

@Slf4j
public class ListFolderDataFetcher extends AbstractDataFetcher<Flux<FullDocumentInfo>, FullDocumentInfo> {


    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = listFolderCriteria;
        initFromClause();
    }

    protected void initFromClause() {
        fromClause = FROM_DOCUMENTS;
    }


    @Override
    public Flux<FullDocumentInfo> get(DataFetchingEnvironment environment) throws Exception {
        List<String> sqlFields = getSqlFields(environment);
        StringBuilder query = toSelect(sqlFields).append(fromClause);
        ListFolderRequest filter = null;
        DatabaseClient.GenericExecuteSpec sqlQuery = null;
        if(environment.getArguments() != null) {
            Object request = environment.getArguments().get(GraphQlQueryConfig.GRAPHQL_REQUEST);
            if(request != null) {
                filter = objectMapper.convertValue(request, ListFolderRequest.class);
                if(filter.pageInfo() == null || filter.pageInfo().pageSize() == null || filter.pageInfo().pageNumber() == null) {
                    throw new IllegalArgumentException("Paging information must be provided");
                }
                criteria.checkFilter(filter);
                criteria.checkPageInfo(filter);
                applyFilter(query, filter);
                applySort(query, filter);
                appendOffsetLimit(query, filter);
                sqlQuery = prepareQuery(environment, query, filter);
            }
        }
        if(sqlQuery == null) {
            throw new IllegalArgumentException("At least paging information is required");
        }
        log.debug("GraphQL - SQL query : {}", query);
        return sqlQuery.map(mapResultFunction(sqlFields))
                .all();
    }

    protected void applyFilter(StringBuilder query, ListFolderRequest filter) {
        criteria.applyFilter(prefix, query, filter);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, StringBuilder query, ListFolderRequest filter) {
        return criteria.bindCriteria(databaseClient.sql(query.toString()), filter);
    }

    private void applySort(StringBuilder query, ListFolderRequest request) {
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

    private void appendOffsetLimit(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.LIMIT).append(request.pageInfo().pageSize())
                .append(SqlUtils.OFFSET).append((request.pageInfo().pageNumber() - 1) * request.pageInfo().pageSize());
    }

}
