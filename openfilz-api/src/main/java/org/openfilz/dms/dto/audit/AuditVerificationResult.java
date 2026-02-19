package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

@Schema(description = "Result of audit chain integrity verification")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditVerificationResult(
        AuditVerificationStatus status,
        long totalEntries,
        long verifiedEntries,
        OffsetDateTime verifiedAt,
        BrokenLink brokenLink
) {
    public record BrokenLink(
            long entryId,
            String expectedHash,
            String actualHash
    ) {}

    public enum AuditVerificationStatus { VALID, BROKEN, EMPTY }
}
