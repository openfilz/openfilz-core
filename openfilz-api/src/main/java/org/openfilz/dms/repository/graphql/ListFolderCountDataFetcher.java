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
import java.util.function.Function;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;

public class ListFolderCountDataFetcher  extends AbstractDataFetcher<Mono<Long>, Long> {

    public static final String COUNT = "count(*)";

    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderCountDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = listFolderCriteria;
        initFromClause();
    }

    protected void initFromClause() {
        fromClause = FROM_DOCUMENTS;
    }

    @Override
    public Mono<Long> get(DataFetchingEnvironment environment) throws Exception {
        StringBuilder query = new StringBuilder(SqlUtils.SELECT).append(COUNT).append(fromClause);
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
                applyFilter(query, filter);
                sqlQuery = prepareQuery(null, query, filter);
            }
        }
        if(sqlQuery == null) {
            sqlQuery = databaseClient.sql(query.toString());
        }
        //log.debug("GraphQL - SQL query : {}", query);
        return sqlQuery.map(getCountMappingFunction())
                .one();
    }

    protected void applyFilter(StringBuilder query, ListFolderRequest filter) {
        criteria.applyFilter(prefix, query, filter);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, StringBuilder query, ListFolderRequest filter) {
        return criteria.bindCriteria(databaseClient.sql(query.toString()), filter);
    }


}
