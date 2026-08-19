package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@JsonTypeName(AuditLogDetails.RESTORE_VERSION)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.RESTORE_VERSION)
public class RestoreVersionAudit extends AuditLogDetails {

    @Schema(description = "Name of the file whose version was restored")
    private String filename;

    @Schema(description = "Version identifier that was restored")
    private String restoredFromVersionId;

    @Schema(description = "Creation date of the restored version")
    private OffsetDateTime restoredFromDate;

    @Schema(description = "Identifier of the new latest version created by the restore")
    private String versionId;
}
