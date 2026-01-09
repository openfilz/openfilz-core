package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.entity.SqlColumnMapping.ID;
import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.WHERE;

@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DocumentDataFetcher extends AbstractDataFetcher<UUID, FullDocumentInfo> {

    protected String fromDocumentsWhere;

    public DocumentDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, DocumentFields documentFields) {
        super(databaseClient, mapper, objectMapper, sqlUtils, documentFields);
    }

    @Override
    protected void initFromWhereClause() {
        fromDocumentsWhere = FROM_DOCUMENTS + WHERE;
    }

    @Override
    public Mono<FullDocumentInfo> get(UUID uuid, DataFetchingEnvironment environment) {
        List<String> sqlFields = getSqlFields(environment);
        StringBuilder query = toSelect(sqlFields).append(fromDocumentsWhere);
        applyFilter(query);
        return prepareQuery(environment, uuid, query)
                .map(mapFullDocumentInfo(sqlFields))
                .one();
    }

    protected void applyFilter(StringBuilder query) {
        sqlUtils.appendEqualsCriteria(prefix, ID, query);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, UUID uuid, StringBuilder query) {
        return sqlUtils.bindCriteria(ID, uuid, databaseClient.sql(query.toString()));
    }

}
