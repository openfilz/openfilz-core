package org.openfilz.dms.repository;

import io.r2dbc.spi.Readable;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.enums.DocumentType;

import java.util.Optional;
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

    default Function<Readable, Optional<UUID>> mapIdOptional() {
        return row -> Optional.ofNullable(row.get(0, UUID.class));
    }

    default Function<Readable, FolderElementInfo> mapFolderElementInfo() {
        return row -> new FolderElementInfo(row.get(ID, UUID.class),
                DocumentType.valueOf(row.get(TYPE, String.class)),
                row.get(NAME, String.class));
    }

}
