package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import io.r2dbc.spi.Readable;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

import static org.openfilz.dms.utils.SqlUtils.FROM_DOCUMENTS;
import static org.openfilz.dms.utils.SqlUtils.SPACE;

@Slf4j
@Service
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class ListFolderDataFetcher extends AbstractListDataFetcher<FullDocumentInfo> implements UserInfoService {


    protected final ListFolderCriteria criteria;

    protected String fromClause;

    public ListFolderDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, @Qualifier("defaultListFolderCriteria") ListFolderCriteria listFolderCriteria) {
        super(databaseClient, mapper, objectMapper, sqlUtils);
        this.criteria = listFolderCriteria;
        this.prefix = "d."; // Set prefix since we use alias 'd' in FROM clause
    }

    @Override
    protected void initFromWhereClause() {
        fromClause = " from Documents d LEFT JOIN user_favorites uf ON d.id = uf.document_id";
    }

    @Override
    protected List<String> getSqlFields(DataFetchingEnvironment environment) {
        List<String> fields = super.getSqlFields(environment);
        // Filter out is_favorite as it will be added as a computed field
        return fields.stream()
                .filter(field -> !"is_favorite".equals(field))
                .toList();
    }

    @Override
    protected Function<Readable, FullDocumentInfo> mapFullDocumentInfo(List<String> sqlFields) {
        return row -> {
            Document document = buildDocument(row, sqlFields);

            // Read is_favorite from the result set if it exists
            Boolean isFavorite = null;
            try {
                isFavorite = row.get("is_favorite", Boolean.class);
            } catch (Exception e) {
                // Column doesn't exist, leave as null
            }

            // Set the isFavorite field on the document
            document.setIsFavorite(isFavorite);

            return mapper.toFullDocumentInfo(document);
        };
    }

    @Override
    public Flux<FullDocumentInfo> get(ListFolderRequest filter, DataFetchingEnvironment environment) {
        List<String> sqlFields = getSqlFields(environment);

        // Check if isFavorite was requested
        boolean includeIsFavorite = environment.getSelectionSet().contains("isFavorite");

        if(filter.pageInfo() == null || filter.pageInfo().pageSize() == null || filter.pageInfo().pageNumber() == null) {
            throw new IllegalArgumentException("Paging information must be provided");
        }
        StringBuilder query = getSelectRequest(sqlFields);

        // Add isFavorite as computed field if requested
        if (includeIsFavorite) {
            int fromIndex = query.indexOf(" from");
            if (fromIndex > 0) {
                query.insert(fromIndex, ", CASE WHEN uf.id IS NOT NULL THEN TRUE ELSE FALSE END as is_favorite");
            }
        }

        criteria.checkFilter(filter);
        criteria.checkPageInfo(filter);
        applyFilter(query, filter);
        applySort(query, filter);
        appendOffsetLimit(query, filter);

        // Get user email reactively and execute query
        return getConnectedUserEmail()
                .flatMapMany(userId -> {
                    DatabaseClient.GenericExecuteSpec sqlQuery = prepareQueryWithUserId(environment, filter, query, userId);
                    log.debug("GraphQL - SQL query : {}", query);
                    return sqlQuery.map(mapFullDocumentInfo(sqlFields)).all();
                });
    }

    protected StringBuilder getSelectRequest(List<String> sqlFields) {
        return toSelect(sqlFields).append(fromClause);
    }

    protected void applyFilter(StringBuilder query, ListFolderRequest filter) {
        criteria.applyFilter(prefix, query, filter);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query) {
        return prepareQueryWithUserId(environment, filter, query, ANONYMOUS_USER);
    }

    protected DatabaseClient.GenericExecuteSpec prepareQueryWithUserId(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query, String userId) {
        // Add AND condition for user_id in the JOIN
        String queryStr = query.toString().replace(
                "LEFT JOIN user_favorites uf ON d.id = uf.document_id",
                "LEFT JOIN user_favorites uf ON d.id = uf.document_id AND uf.user_id = :user_id"
        );

        DatabaseClient.GenericExecuteSpec spec = criteria.bindCriteria(databaseClient.sql(queryStr), filter);

        // Bind user_id
        return spec.bind("user_id", userId != null ? userId : "");
    }

    public void applySort(StringBuilder query, ListFolderRequest request) {
        if(request.pageInfo().sortBy() != null) {
            appendSort(query, request);
        }
    }

    private void appendSort(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.ORDER_BY).append(getSortByField(request));
        if(request.pageInfo().sortOrder() != null) {
            query.append(SPACE).append(request.pageInfo().sortOrder());
        }
    }

    private String getSortByField(ListFolderRequest request) {
        String sortBy = DOCUMENT_FIELD_SQL_MAP.get(request.pageInfo().sortBy());
        return prefix == null ? sortBy : prefix + sortBy;
    }

    public void appendOffsetLimit(StringBuilder query, ListFolderRequest request) {
        query.append(SqlUtils.LIMIT).append(request.pageInfo().pageSize())
                .append(SqlUtils.OFFSET).append((request.pageInfo().pageNumber() - 1) * request.pageInfo().pageSize());
    }

}
