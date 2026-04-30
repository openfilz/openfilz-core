
package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;

import java.time.OffsetDateTime;
import java.util.UUID;

@Schema(description = "Audit information")
@JsonInclude(JsonInclude.Include.NON_NULL)
// Forward-compatibility: tolerate extra fields. The EE audit endpoint
// returns an enriched DTO that adds `azp` / `actedViaIntegration` to this
// shape via @JsonUnwrapped — clients deserializing the legacy core shape
// (open-source consumers, the SDKs, EE tests inherited from core) must not
// fail on those new fields. This is a hardening change only; it doesn't
// affect serialization or any other behavior.
@JsonIgnoreProperties(ignoreUnknown = true)
public record AuditLog(
        UUID id,
        OffsetDateTime timestamp,
        String username,
        AuditAction action,
        DocumentType resourceType,
        AuditLogDetails details,
        String previousHash,
        String hash) {
}
