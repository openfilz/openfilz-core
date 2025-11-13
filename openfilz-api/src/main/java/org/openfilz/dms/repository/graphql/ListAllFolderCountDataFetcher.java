package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListAllFolderCountDataFetcher extends ListFolderCountDataFetcher {

    public ListAllFolderCountDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("allFolders") ListAllFolderCriteria criteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils, criteria);
    }
}
