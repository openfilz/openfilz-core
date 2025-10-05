package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.config.GraphQlQueryConfig;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;

public class ListFolderCountDataFetcher  extends AbstractListDataFetcher<Mono<Long>, Long> {

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
        DatabaseClient.GenericExecuteSpec sqlQuery;
        StringBuilder query = new StringBuilder(SqlUtils.SELECT).append(COUNT).append(fromClause);
        Map<String, Object> arguments = environment.getArguments();
        if(arguments == null || arguments.get(GraphQlQueryConfig.GRAPHQL_REQUEST) == null) {
            sqlQuery = databaseClient.sql(query.toString());
        } else {
            Object request = arguments.get(GraphQlQueryConfig.GRAPHQL_REQUEST);
            ListFolderRequest filter = getFilter(request);
            if(filter.pageInfo() != null &&  (filter.pageInfo().pageSize() != null || filter.pageInfo().pageNumber() != null)) {
                throw new IllegalArgumentException("Paging information must not be provided");
            }
            criteria.checkFilter(filter);
            applyFilter(query, filter);
            sqlQuery = prepareQuery(environment, filter, query);
        }

        //log.debug("GraphQL - SQL query : {}", query);
        return sqlQuery.map(mapCount())
                .one();
    }

    protected void applyFilter(StringBuilder query, ListFolderRequest filter) {
        criteria.applyFilter(prefix, query, filter);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query) {
        return criteria.bindCriteria(databaseClient.sql(query.toString()), filter);
    }


}
