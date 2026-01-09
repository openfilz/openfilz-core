package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;


public abstract class AbstractListDataFetcher<T> extends AbstractDataFetcher<ListFolderRequest, T> {


    public AbstractListDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, DocumentFields documentFields) {
        super(databaseClient, mapper, objectMapper, sqlUtils, documentFields);
    }

    private void appendLeftJoin(StringBuilder sb) {
        sb.append(" LEFT");
    }

    private void appendFavorites(StringBuilder sb) {
        sb.append(" JOIN user_favorites uf ON d.id = uf.doc_id and uf.email = :email ");
    }

    protected void appendRemainingFromClause(boolean includeIsFavorite, Boolean favoriteFilter, StringBuilder sb) {
        if (includeIsFavorite) {
            if(favoriteFilter == null || !favoriteFilter) {
                appendLeftJoin(sb);
            }
            appendFavorites(sb);
        } else if(favoriteFilter != null) {
            if(!favoriteFilter) {
                appendLeftJoin(sb);
            }
            appendFavorites(sb);
        }
    }

    protected DatabaseClient.GenericExecuteSpec bindFavoritesToNewQuery(DataFetchingEnvironment environment, StringBuilder query, boolean withFavorites) {
        if(withFavorites) {
            String email = environment.getGraphQlContext().get(EMAIL);
            DatabaseClient.GenericExecuteSpec sql = databaseClient.sql(query.toString());
            sql = sql.bind("email", email);
            return sql;
        }
        return databaseClient.sql(query.toString());
    }

    protected DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, ListFolderRequest filter, StringBuilder query) {
        boolean includeIsFavorite = environment != null && getSelectedFields(environment).anyMatch(f -> f.getName().equals("favorite"));
        return bindFavoritesToNewQuery(environment, query, includeIsFavorite || (filter != null && filter.favorite() != null));
    }


}
