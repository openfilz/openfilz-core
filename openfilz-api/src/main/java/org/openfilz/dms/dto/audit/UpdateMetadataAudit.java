package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Map;

@JsonTypeName(AuditLogDetails.UPDATE_METADATA)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.UPDATE_METADATA)
public class UpdateMetadataAudit extends AuditLogDetails {

    @Schema(description = "Updated Metadata")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private Map<String, Object> updatedMetadata;

}
