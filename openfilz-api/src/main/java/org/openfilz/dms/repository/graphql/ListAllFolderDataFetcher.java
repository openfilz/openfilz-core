package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.SqlColumnMapping;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListAllFolderDataFetcher extends ListFolderDataFetcher {

    public ListAllFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("allFolders") ListAllFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils, listFolderCriteria);
    }

    protected List<String> getSqlFields(DataFetchingEnvironment environment) {
        List<String> sqlFields = super.getSqlFields(environment);
        if(!sqlFields.contains(SqlColumnMapping.TYPE)) {
            List<String> newList = new ArrayList<>(sqlFields);
            newList.add(SqlColumnMapping.TYPE);
            return  newList;
        }
        return sqlFields;
    }

}
