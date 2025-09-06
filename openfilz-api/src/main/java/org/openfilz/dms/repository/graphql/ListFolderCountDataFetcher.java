package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import io.r2dbc.spi.Readable;
import org.openfilz.dms.config.GraphQlQueryConfig;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.List;

public class ListFolderCountDataFetcher  extends AbstractDataFetcher<Mono<Long>, Long> {

    public static final String COUNT = "count(*)";
    private final ListFolderCriteria criteria;

    public ListFolderCountDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = listFolderCriteria;
    }

    @Override
    protected Long mapResultRow(Readable row, List<String> sqlFields) {
        return row.get(0, Long.class);
    }

    @Override
    public Mono<Long> get(DataFetchingEnvironment environment) throws Exception {
        StringBuilder query = new StringBuilder(SqlUtils.SELECT).append(COUNT).append(SqlUtils.FROM_DOCUMENTS);
        ListFolderRequest filter = null;
        DatabaseClient.GenericExecuteSpec sqlQuery = null;
        if(environment.getArguments() != null) {
            Object request = environment.getArguments().get(GraphQlQueryConfig.GRAPHQL_REQUEST);
            if(request != null) {
                filter = objectMapper.convertValue(request, ListFolderRequest.class);
                if(filter.pageInfo() != null &&  (filter.pageInfo().pageSize() != null || filter.pageInfo().pageNumber() != null)) {
                    throw new IllegalArgumentException("Paging information must not be provided");
                }
                criteria.checkFilter(filter);
                criteria.applyFilter(prefix, query, filter);
                sqlQuery = criteria.bindCriteria(databaseClient.sql(query.toString()), filter);
            }
        }
        if(sqlQuery == null) {
            sqlQuery = databaseClient.sql(query.toString());
        }
        //log.debug("GraphQL - SQL query : {}", query);
        return sqlQuery.map(row -> mapResultRow(row, null))
                .one();
    }
}
