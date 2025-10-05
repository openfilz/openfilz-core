package org.openfilz.dms.repository.graphql;

import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.SelectedField;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.FullDocumentInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.mapper.DocumentMapper;
import org.openfilz.dms.repository.SqlQueryUtils;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.data.util.ParsingUtils;
import org.springframework.r2dbc.core.DatabaseClient;

import java.beans.FeatureDescriptor;
import java.beans.IntrospectionException;
import java.beans.Introspector;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.openfilz.dms.entity.SqlColumnMapping.*;

@RequiredArgsConstructor
public abstract class AbstractDataFetcher<T, R, Z> implements DataFetcher<T>, SqlQueryUtils {

    protected static final Map<String, String> DOCUMENT_FIELD_SQL_MAP;

    static {
        try {
            DOCUMENT_FIELD_SQL_MAP = Arrays.stream(Introspector.getBeanInfo(Document.class)
                            .getPropertyDescriptors())
                    .collect(Collectors.toMap(FeatureDescriptor::getName,
                            pd -> ParsingUtils.reconcatenateCamelCase(pd.getName(), SqlUtils.UNDERSCORE)));
        } catch (IntrospectionException e) {
            throw new RuntimeException(e);
        }
    }

    protected String prefix = null;

    protected final DatabaseClient databaseClient;
    protected final DocumentMapper mapper;
    protected final ObjectMapper objectMapper;
    protected final SqlUtils sqlUtils;

    protected List<String> getSqlFields(DataFetchingEnvironment environment) {
        List<SelectedField> objectFields = environment.getSelectionSet().getFields();
        return objectFields.stream()
                .map(field -> DOCUMENT_FIELD_SQL_MAP.get(field.getName()))
                .filter(Objects::nonNull)
                .toList();
    }

    protected StringBuilder toSelect(List<String> fields) {
        return new StringBuilder(SqlUtils.SELECT).append(String.join(SqlUtils.COMMA, prefix == null ? fields : fields.stream().map(s->prefix + s).toList()));
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
            }
        });
        return builder.build();
    }

    protected Function<Readable, FullDocumentInfo> mapFullDocumentInfo(List<String> sqlFields) {
        return row -> mapper.toFullDocumentInfo(buildDocument(row, sqlFields));
    }

    protected abstract DatabaseClient.GenericExecuteSpec prepareQuery(DataFetchingEnvironment environment, Z requestCriteria, StringBuilder query);

}
