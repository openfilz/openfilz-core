package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;


public abstract class AbstractListDataFetcher<T> extends AbstractDataFetcher<ListFolderRequest, T> {

    public AbstractListDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
    }

    protected ListFolderRequest getFilter(Object request) {
        return objectMapper.convertValue(request, ListFolderRequest.class);
    }

}
