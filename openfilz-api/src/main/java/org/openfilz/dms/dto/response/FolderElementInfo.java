package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.DocumentType;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FolderElementInfo(
        @Schema(description = "ID of the document") UUID id,
        @Schema(description = "Type of the document") DocumentType type,
        @Schema(description = "Name of the document") String name,
        @Schema(description = "E-mail of the document's owner. Given by the folder listing (GET /folders/list), absent elsewhere")
        String owner) {

    public FolderElementInfo(UUID id, DocumentType type, String name) {
        this(id, type, name, null);
    }
}
