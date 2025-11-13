package org.openfilz.dms.repository;

import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Readable;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.function.Function;

import static org.openfilz.dms.entity.SqlColumnMapping.*;

public interface SqlQueryUtils {

    default Function<Readable, Long> mapCount() {
        return row -> row.get(0, Long.class);
    }

    default Function<Readable, UUID> mapId() {
        return row -> row.get(0, UUID.class);
    }

    default Function<Readable, FolderElementInfo> mapFolderElementInfo() {
        return row -> {
            Json metadata = row.get(METADATA, Json.class);
            String metadataString = metadata != null ? metadata.asString() : null;

            return new FolderElementInfo(
                    row.get(ID, UUID.class),
                    DocumentType.valueOf(row.get(TYPE, String.class)),
                    row.get(CONTENT_TYPE, String.class),
                    row.get(NAME, String.class),
                    metadataString,
                    row.get(SIZE, Long.class),
                    row.get(CREATED_AT, OffsetDateTime.class),
                    row.get(UPDATED_AT, OffsetDateTime.class),
                    row.get(CREATED_BY, String.class),
                    row.get(UPDATED_BY, String.class),
                    row.get(IS_FAVORITE, Boolean.class)
            );
        };
    }

}
