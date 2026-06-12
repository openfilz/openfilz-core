package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@JsonTypeName(AuditLogDetails.UPLOAD)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.UPLOAD)
public class UploadAudit extends AuditLogDetails {

    public UploadAudit(String filename, UUID parentFolderId, Map<String, Object> metadata) {
        this(filename, parentFolderId, metadata, null);
    }

    private String filename;
    private UUID parentFolderId;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> metadata;
    @Schema(description = "Storage version identifier created by this upload (only when versioning is enabled)")
    private String versionId;
}
