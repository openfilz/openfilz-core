package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.SqlColumnMapping;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.SPACE;

@Slf4j
@Service("defaultListFolderDataFetcher")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListFolderDataFetcher extends AbstractListDataFetcher<FullDocumentInfo> {


    public static final String CASE_FOLDER = ", CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END as d_type";
    protected static final String D_TYPE = "d_type";
    protected static final String CASE_FAVORITE = ", CASE WHEN uf.doc_id IS NOT NULL THEN TRUE ELSE FALSE END as favorite";

    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, DocumentFields documentFields, @Qualifier("defaultListFolderCriteria") ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils, documentFields);
        this.criteria = listFolderCriteria;
        this.prefix = "d.";
    }

    @Override
    protected void initFromWhereClause() {
        fromClause = FROM_DOCUMENTS + " d";
    }

    @Override
    public Flux<FullDocumentInfo> get(ListFolderRequest filter, DataFetchingEnvironment environment) {
        List<String> sqlFields = getSqlFields(environment);

        // Check if isFavorite was requested
        boolean includeIsFavorite = getSelectedFields(environment).anyMatch(f -> f.getName().equals(SqlColumnMapping.FAVORITE));

        if(filter.pageInfo() == null || filter.pageInfo().pageSize() == null || filter.pageInfo().pageNumber() == null) {
            throw new IllegalArgumentException("Paging information must be provided");
        }
        StringBuilder query = getSelectRequest(sqlFields, includeIsFavorite, filter.favorite());

        criteria.checkFilter(filter);
        criteria.checkPageInfo(filter);
        applyFilter(filter, prefix, query);
        applySort(query, filter);
        appendOffsetLimit(query, filter);
        DatabaseClient.GenericExecuteSpec sqlQuery = prepareQuery(environment, filter, query);
        log.debug("GraphQL - SQL query : {}", query);
        if(includeIsFavorite) {
            List<String> newFieldsList = new ArrayList<>(sqlFields);
            newFieldsList.add(SqlColumnMapping.FAVORITE);
            return getDocuments(sqlQuery, newFieldsList);
        }
        return getDocuments(sqlQuery, sqlFields);
    }

    private  Flux<FullDocumentInfo> getDocuments(DatabaseClient.GenericExecuteSpec sqlQuery, List<String> newFieldsList) {
        return sqlQuery.map(mapFullDocumentInfo(newFieldsList)).all();
    }

    protected StringBuilder getSelectRequest(List<String> sqlFields, boolean includeIsFavorite, Boolean favoriteFilter) {
        StringBuilder sb = getSelectFieldsPart(sqlFields, includeIsFavorite, favoriteFilter);
        sb.append(fromClause);
        appendRemainingFromClause(includeIsFavorite, favoriteFilter, sb);
        return sb;

    }

    protected StringBuilder getSelectFieldsPart(List<String> sqlFields, boolean includeIsFavorite, Boolean favoriteFilter) {
        StringBuilder sb = toSelect(sqlFields);
        // Add isFavorite as computed field if requested
        if (includeIsFavorite || (favoriteFilter != null && !favoriteFilter)) {
            sb.append(CASE_FAVORITE);
        }
        sb.append(CASE_FOLDER);
        return sb;
    }

    protected void applyFilter(ListFolderRequest filter, String newPrefix, StringBuilder query) {
        criteria.applyFilter(newPrefix, query, filter);
    }



    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query) {
        return criteria.bindCriteria(super.prepareQuery(environment, filter, query), filter);
    }

    public void applySort(StringBuilder query, ListFolderRequest request) {
        prepareSort(query).append(D_TYPE);
        if(request.pageInfo().sortBy() != null) {
            query.append(", ");
            appendSort(query, request);
        }
    }

    public StringBuilder prepareSort(StringBuilder query) {
        return query.append(SqlUtils.ORDER_BY);
    }

    public void appendSort(StringBuilder query, ListFolderRequest request) {
        query.append(getSortByField(request));
        if(request.pageInfo().sortOrder() != null) {
            query.append(SPACE).append(request.pageInfo().sortOrder());
        }
    }

    private String getSortByField(ListFolderRequest request) {
        String sortBy = documentFields.getDocumentFieldSqlMap().get(request.pageInfo().sortBy());
        return prefix == null ? sortBy : prefix + sortBy;
    }

    public void appendOffsetLimit(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.LIMIT).append(request.pageInfo().pageSize())
                .append(SqlUtils.OFFSET).append((request.pageInfo().pageNumber() - 1) * request.pageInfo().pageSize());
    }

}
