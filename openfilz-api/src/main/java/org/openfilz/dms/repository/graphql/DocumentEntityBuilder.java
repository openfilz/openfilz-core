package org.openfilz.dms.repository.graphql;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.entity.SqlColumnMapping.CONTENT_TYPE;
import static org.openfilz.dms.entity.SqlColumnMapping.CREATED_AT;
import static org.openfilz.dms.entity.SqlColumnMapping.CREATED_BY;
import static org.openfilz.dms.entity.SqlColumnMapping.FAVORITE;
import static org.openfilz.dms.entity.SqlColumnMapping.METADATA;
import static org.openfilz.dms.entity.SqlColumnMapping.SIZE;
import static org.openfilz.dms.entity.SqlColumnMapping.TYPE;
import static org.openfilz.dms.entity.SqlColumnMapping.UPDATED_AT;
import static org.openfilz.dms.entity.SqlColumnMapping.UPDATED_BY;

public interface DocumentEntityBuilder {

    default Document buildDocument(Readable row, List<String> fields) {
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
}
