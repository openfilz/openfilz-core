package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@JsonTypeName(AuditLogDetails.COPY)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.COPY)
public class CopyAudit extends AuditLogDetails {

    public CopyAudit(UUID sourceFileId, UUID targetFolderId) {
        this(sourceFileId, targetFolderId, null);
    }

    private UUID sourceFileId;
    private UUID targetFolderId;
    private UUID sourceFolderId;
}
