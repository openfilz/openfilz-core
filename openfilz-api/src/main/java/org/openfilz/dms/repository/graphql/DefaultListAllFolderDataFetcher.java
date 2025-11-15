package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.ListAllFolderDataFetcher;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service("defaultListFolderDataFetcher")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class DefaultListAllFolderDataFetcher extends ListFolderDataFetcher implements ListAllFolderDataFetcher {

    public DefaultListAllFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("allFolders") ListAllFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
    }

    @Override
    protected List<String> getSqlFields(DataFetchingEnvironment environment) {
        return getCustomSqlFields(environment);
    }

    @Override
    public List<String> getAllSqlFields(DataFetchingEnvironment environment) {
        return super.getSqlFields(environment);
    }
}
