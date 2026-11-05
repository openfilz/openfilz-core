package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@JsonTypeName(AuditLogDetails.EDITOR_CONFLICT)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.EDITOR_CONFLICT)
public class EditorConflictAudit extends AuditLogDetails {

    @Schema(description = "Key of the editor session whose save did not start from the current content")
    private String sessionKey;

    @Schema(description = "Id of the document holding the editor's work; absent when it could not be created")
    private UUID copyId;

    @Schema(description = "Name of the document holding the editor's work")
    private String copyName;

    @Schema(description = "Why the copy could not be created, when it could not")
    private String failure;
}
