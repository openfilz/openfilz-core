package org.openfilz.dms.dto.audit;


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@JsonTypeName(AuditLogDetails.DELETE_METADATA)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.DELETE_METADATA)
public class DeleteMetadataAudit extends AuditLogDetails {

    @Schema(description = "List of deleted metadata keys")
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    private List<String> deletedMetadataKeys;

}
