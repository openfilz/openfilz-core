
package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Audit information")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditLog(
        UUID id,
        OffsetDateTime timestamp,
        String username,
        AuditAction action,
        DocumentType resourceType,
        AuditLogDetails details) {
}
