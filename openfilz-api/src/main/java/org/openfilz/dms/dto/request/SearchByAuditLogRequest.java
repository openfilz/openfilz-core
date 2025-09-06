package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;

import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Documents matching all provided criteria will be returned")
public record SearchByAuditLogRequest(
        @Schema(description = "Username who made the action")
        String username,
        @Schema(description = "Document ID to search for")
        UUID id,
        @Schema(description = "Type of the document to search - if not provided or null : search all types")
        DocumentType type,
        @Schema(description = "Action to search for - if not provided or null : search all actions")
        AuditAction action,
        @Schema(description = "Audit Metadata key-value pairs to search for")
        Map<String, Object> details
) {
}