package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.UUID;

@JsonTypeName(AuditLogDetails.UPLOAD)
@Getter
@RequiredArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.UPLOAD)
public class UploadAudit extends AuditLogDetails {
    private final String filename;
    private final UUID parentFolderId;
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private final Map<String, Object> metadata;
}
