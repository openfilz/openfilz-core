package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openfilz.dms.enums.DocumentTemplateType;

import java.util.UUID;

public record CreateBlankDocumentRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @NotNull @Schema(description = "Type of blank document to create: WORD, EXCEL, POWERPOINT, or TEXT") DocumentTemplateType documentType,
        @Schema(description = "ID of the parent folder. If null, created at root.") UUID parentFolderId
) {
}
