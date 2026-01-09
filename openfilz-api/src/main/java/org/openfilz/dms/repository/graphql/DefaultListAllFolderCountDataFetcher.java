package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.ListAllFolderCountDataFetcher;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultListAllFolderCountDataFetcher extends ListFolderCountDataFetcher implements ListAllFolderCountDataFetcher {

    public DefaultListAllFolderCountDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("allFolders") ListAllFolderCriteria criteria, DocumentFields documentFields) {
        super(databaseClient, mapper, objectMapper, sqlUtils, criteria, documentFields);
    }

    @Override
    public List<String> getAllSqlFields(DataFetchingEnvironment environment) {
        return super.getSqlFields(environment);
    }
}
