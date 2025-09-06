package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateFolderRequest(
        @NotBlank @Size(min = 1, max = 255) String name,
        @Schema(description = "ID of the parent folder. If null, created at root.") UUID parentId
) {
}
