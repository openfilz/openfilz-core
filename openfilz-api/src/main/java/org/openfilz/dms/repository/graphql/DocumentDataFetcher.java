package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import io.r2dbc.spi.Readable;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.openfilz.dms.entity.SqlColumnMapping.ID;
import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.WHERE;

public class DocumentDataFetcher extends AbstractDataFetcher<Mono<FullDocumentInfo>, FullDocumentInfo> {

    protected String fromDocumentsWhere;

    public DocumentDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        initFromWhereClause();
    }

    protected void initFromWhereClause() {
        fromDocumentsWhere = FROM_DOCUMENTS + WHERE;
    }

    @Override
    public Mono<FullDocumentInfo> get(DataFetchingEnvironment environment) throws Exception {
        List<String> sqlFields = getSqlFields(environment);
        StringBuilder query = toSelect(sqlFields).append(fromDocumentsWhere);
        applyFilter(query);
        UUID uuid = (UUID) environment.getArguments().get(ID);
        return prepareQuery(environment, uuid, query)
                .map(mapResultFunction(sqlFields))
                .one();
    }

    protected void applyFilter(StringBuilder query) {
        sqlUtils.appendEqualsCriteria(prefix, ID, query);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, UUID uuid, StringBuilder query) {
        return sqlUtils.bindCriteria(ID, uuid, databaseClient.sql(query.toString()));
    }

}
