package org.openfilz.dms.dto.request.pdf;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.UUID;

/**
 * Where the parts of a split go. Parts are always new documents.
 *
 * @param folderId                target folder; null = the folder of the source
 * @param namePattern             name of each part, with placeholders {@code {name}} (source name without
 *                                extension), {@code {index}} (1-based, zero-padded), {@code {first}}/{@code {last}}
 *                                (page numbers) and {@code {title}} (bookmark title, BY_OUTLINE_LEVEL only).
 *                                Default {@code {name}-{index}}.
 * @param createSubfolder         create a sub-folder named after the source inside the target folder and put the
 *                                parts there (default false)
 * @param allowDuplicateFileNames accept names already present in the target folder (default false)
 */
@Schema(description = "Destination of the parts of a split")
public record SplitOutput(
        @Schema(description = "Target folder; null = folder of the source") UUID folderId,
        @Schema(description = "Name pattern with {name}, {index}, {first}, {last}, {title}; default \"{name}-{index}\"") String namePattern,
        @Schema(description = "Create a sub-folder named after the source for the parts (default false)") Boolean createSubfolder,
        @Schema(description = "Allow names already present in the folder (default false)") Boolean allowDuplicateFileNames) {

    public static SplitOutput defaults() {
        return new SplitOutput(null, null, null, null);
    }
}
