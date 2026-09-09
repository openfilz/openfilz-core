package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * Cached verdict of the last <em>scheduled</em> audit-chain verification on this instance.
 *
 * <p>Cheap to poll, unlike {@code GET /audit/verify} which re-walks the whole chain.
 */
@Schema(description = "Last known result of the scheduled audit chain verification on this instance")
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuditIntegrityStatusResponse(

        @Schema(description = "true when the last scheduled pass reported a broken chain")
        boolean chainBroken,

        @Schema(description = "false until the first scheduled verification has completed on this "
                + "instance, or when the last one could not run. Not verified is not the same as valid.")
        boolean verified,

        @Schema(description = "Verdict of the last completed scheduled verification, if any")
        AuditVerificationResult lastResult,

        @Schema(description = "Why the last scheduled verification could not run, if it failed")
        String lastFailureMessage,

        @Schema(description = "When the last scheduled verification failed to run, if it did")
        OffsetDateTime lastFailureAt
) {}
