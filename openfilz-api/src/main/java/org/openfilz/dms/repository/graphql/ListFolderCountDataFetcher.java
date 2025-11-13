package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;

@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListFolderCountDataFetcher  extends AbstractListDataFetcher<Long> {

    public static final String COUNT = "count(*)";

    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderCountDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("defaultListFolderCriteria") ListFolderCriteria criteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = criteria;
    }


    @Override
    protected void initFromWhereClause() {
        fromClause = FROM_DOCUMENTS;
    }

    @Override
    public Mono<Long> get(ListFolderRequest filter, DataFetchingEnvironment environment) {
        DatabaseClient.GenericExecuteSpec sqlQuery;
        StringBuilder query = new StringBuilder(SqlUtils.SELECT).append(COUNT).append(fromClause);
        if(filter == null) {
            sqlQuery = databaseClient.sql(query.toString());
        } else {
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
