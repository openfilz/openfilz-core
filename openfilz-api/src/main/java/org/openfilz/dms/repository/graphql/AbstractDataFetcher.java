package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.SqlQueryUtils;
import org.openfilz.dms.utils.SqlUtils;
import org.reactivestreams.Publisher;
import org.springframework.r2dbc.core.DatabaseClient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.openfilz.dms.entity.SqlColumnMapping.*;

public abstract class AbstractDataFetcher<T, R> implements SqlQueryUtils {

    protected String prefix = null;

    protected final DatabaseClient databaseClient;
    protected final DocumentMapper mapper;
    protected final ObjectMapper objectMapper;
    protected final SqlUtils sqlUtils;
    protected final DocumentFields documentFields;

    public AbstractDataFetcher(DatabaseClient databaseClient, DocumentMapper mapper, ObjectMapper objectMapper, SqlUtils sqlUtils, DocumentFields documentFields) {
        this.databaseClient = databaseClient;
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.sqlUtils = sqlUtils;
        this.documentFields = documentFields;
        initFromWhereClause();
    }

    protected abstract void initFromWhereClause();

    protected Stream<SelectedField> getSelectedFields(DataFetchingEnvironment environment) {
        return environment
                .getSelectionSet()
                .getFieldsGroupedByResultKey()
                .values()
                .stream()
                .flatMap(List::stream);
    }

    protected List<String> getSqlFields(DataFetchingEnvironment environment) {
        Stream<SelectedField> objectFields = getSelectedFields(environment);
        return objectFields
                .map(field -> documentFields.getDocumentFieldSqlMap().get(field.getName()))
                .filter(Objects::nonNull)
                .toList();
    }

    protected StringBuilder toSelect(List<String> fields) {
        StringBuilder sb = new StringBuilder(SqlUtils.SELECT);
        return sb.append(String.join(SqlUtils.COMMA, prefix == null ? fields : getFieldsToInclude(fields).map(s->prefix + s).toList()));
    }

    protected Stream<String> getFieldsToInclude(List<String> fields) {
        return fields.stream();
    }

    protected Document buildDocument(Readable row, List<String> fields) {
        Document.DocumentBuilder builder = Document.builder();
        fields.forEach(field -> {
            switch (field) {
                case ID -> builder.id(row.get(field, UUID.class));
                case PARENT_ID -> builder.parentId(row.get(field, UUID.class));
                case NAME -> builder.name(row.get(field, String.class));
                case TYPE -> builder.type(DocumentType.valueOf(row.get(field, String.class)));
                case SIZE -> builder.size(row.get(field, Long.class));
                case METADATA -> builder.metadata(row.get(field, Json.class));
                case CREATED_AT -> builder.createdAt(row.get(field, OffsetDateTime.class));
                case UPDATED_AT -> builder.updatedAt(row.get(field, OffsetDateTime.class));
                case CREATED_BY -> builder.createdBy(row.get(field, String.class));
                case UPDATED_BY -> builder.updatedBy(row.get(field, String.class));
                case CONTENT_TYPE -> builder.contentType(row.get(field, String.class));
                case FAVORITE -> builder.favorite(row.get(field, Boolean.class));
            }
        });
        return builder.build();
    }

    protected Function<Readable, FullDocumentInfo> mapFullDocumentInfo(List<String> sqlFields) {
        return row -> mapper.toFullDocumentInfo(buildDocument(row, sqlFields));
    }

    protected abstract DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, T requestCriteria, StringBuilder query);

    protected abstract Publisher<R> get(T parameters, DataFetchingEnvironment environment);
}
