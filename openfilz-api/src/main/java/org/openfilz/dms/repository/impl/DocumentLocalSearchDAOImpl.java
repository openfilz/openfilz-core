package org.openfilz.dms.repository.impl;

import org.openfilz.dms.dto.request.FilterInput;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.request.SortInput;
import org.openfilz.dms.dto.response.Suggest;
import org.openfilz.dms.repository.DocumentLocalSearchDAO;
import org.openfilz.dms.repository.graphql.ListAllFolderCriteria;
import org.openfilz.dms.repository.graphql.ListFolderCriteria;
import org.openfilz.dms.repository.graphql.ListFolderDataFetcher;
import org.openfilz.dms.utils.DocumentSearchUtil;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

@Service
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.full-text.active", havingValue = "false", matchIfMissing = true),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class DocumentLocalSearchDAOImpl implements DocumentLocalSearchDAO {

    public static final String SELECT_DOCS = "select id, name, type from documents";

    private final DatabaseClient databaseClient;
    protected final DocumentSearchUtil documentSearchUtil;
    protected final ListFolderCriteria listFolderCriteria;
    protected final ListAllFolderCriteria listAllFolderCriteria;
    private final ListFolderDataFetcher listFolderDataFetcher;

    public DocumentLocalSearchDAOImpl(DatabaseClient databaseClient, DocumentSearchUtil documentSearchUtil, @Qualifier("defaultListFolderCriteria") ListFolderCriteria listFolderCriteria, ListAllFolderCriteria listAllFolderCriteria, @Qualifier("defaultListFolderDataFetcher") ListFolderDataFetcher listFolderDataFetcher) {
        this.databaseClient = databaseClient;
        this.documentSearchUtil = documentSearchUtil;
        this.listFolderCriteria = listFolderCriteria;
        this.listAllFolderCriteria = listAllFolderCriteria;
        this.listFolderDataFetcher = listFolderDataFetcher;
    }


    @Override
    public Flux<Suggest> getSuggestions(String query, List<FilterInput> filters, SortInput sort) {
        StringBuilder sqlQuery = new StringBuilder(getSuggestionSelectRequest());
        Map<String, String> filterMap = documentSearchUtil.toFilterMap(filters);
        ListFolderRequest listFolderRequest =  documentSearchUtil.toListFolderRequest(query, filterMap, sort, 1, 10);
        boolean allFolders = applyFilters(filterMap, sqlQuery, listFolderRequest);

        if(listFolderRequest.pageInfo().sortBy() != null) {
            listFolderDataFetcher.prepareSort(sqlQuery);
            listFolderDataFetcher.appendSort(sqlQuery, listFolderRequest);
        }

        listFolderDataFetcher.appendOffsetLimit(sqlQuery, listFolderRequest);
        DatabaseClient.GenericExecuteSpec sqlDbQuery = databaseClient.sql(sqlQuery.toString());
        if(allFolders) {
            sqlDbQuery = listAllFolderCriteria.bindCriteria(sqlDbQuery, listFolderRequest);
        } else {
            sqlDbQuery = listFolderCriteria.bindCriteria(sqlDbQuery, listFolderRequest);
        }
        return executeQuery(sqlDbQuery);
    }

    protected boolean applyFilters(Map<String, String> filterMap, StringBuilder sqlQuery, ListFolderRequest listFolderRequest) {
        boolean allFolders = false;
        if(filterMap != null && filterMap.containsKey(DocumentSearchUtil.FILTER_PARENT_ID)) {
            listFolderCriteria.applyFilter(null, sqlQuery, listFolderRequest);
        } else {
            allFolders = true;
            listAllFolderCriteria.applyFilter(null, sqlQuery, listFolderRequest);
        }
        return allFolders;
    }

    protected Flux<Suggest> executeQuery(DatabaseClient.GenericExecuteSpec sql) {
        return sql
                .map(documentSearchUtil::toSuggest)
                .all();
    }

    protected String getSuggestionSelectRequest() {
        return SELECT_DOCS;
    }


}
