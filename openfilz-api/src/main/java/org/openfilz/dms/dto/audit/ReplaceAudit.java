package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@JsonTypeName(AuditLogDetails.REPLACE)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.REPLACE)
public class ReplaceAudit extends AuditLogDetails {

    public ReplaceAudit(String filename) {
        this(filename, null);
    }

    public ReplaceAudit(Map<String, Object> metadata) {
        this(null, metadata);
    }

    @Schema(description = "New file replacing the existing one")
    private String filename;

    @Schema(description = "New metadata replacing the existing ones")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> metadata;

}
