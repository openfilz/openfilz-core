package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Where to extract a ZIP document. The destination is resolved in this order:
 * {@code targetRoot=true} → the root; {@code targetFolderId} → that folder; neither → the folder
 * that contains the ZIP. When {@code newFolderName} is set, a folder of that name is first created
 * in the resolved destination and the archive is extracted into it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UnzipRequest(
        @Schema(description = "Folder to extract into. Omitted (and targetRoot not set): the folder containing the ZIP.") UUID targetFolderId,
        @Schema(description = "Extract at the root, whatever targetFolderId says.") Boolean targetRoot,
        @Schema(description = "Create a folder of this name in the destination and extract into it.") @Size(max = 255) String newFolderName,
        @Schema(description = "When true, files are extracted even if a document of the same name already exists in their destination folder. When false (default), such entries are skipped and listed in the response. Existing folders are always merged.") Boolean allowDuplicateFileNames
) {
}
