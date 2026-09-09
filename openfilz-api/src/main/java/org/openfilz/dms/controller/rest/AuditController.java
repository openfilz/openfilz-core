package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditIntegrityStatusResponse;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.service.AuditIntegrityStatus;
import org.openfilz.dms.service.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_AUDIT;

@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + ENDPOINT_AUDIT)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class AuditController {
    private final AuditService auditService;
    private final AuditIntegrityStatus integrityStatus;

    @GetMapping("/{id}")
    @Operation(summary = "Get audit trail for a resource", description = "Retrieves the audit trail for a given resource.")
    public Flux<AuditLog> getAuditTrail(
            @Parameter(description = "ID of the resource to get the audit trail for") @PathVariable("id") UUID resourceId,
            @Parameter(description = "Sort order for the audit trail. Can be 'ASC' or 'DESC'. Default is 'DESC'") @RequestParam(required = false) SortOrder sort) {
        return auditService.getAuditTrail(resourceId, sort);
    }

    @PostMapping("/search")
    @Operation(summary = "Search for audit trails", description = "Retrieves the audit trail according to the search parameters")
    public Flux<AuditLog> searchAuditTrail(
            @Valid @org.springframework.web.bind.annotation.RequestBody SearchByAuditLogRequest request) {
        return auditService.searchAuditTrail(request);
    }

    @GetMapping("/verify")
    @Operation(summary = "Verify audit chain integrity", description = "Verifies the cryptographic hash chain of the audit log to detect any tampering.")
    public Mono<AuditVerificationResult> verifyChain() {
        return auditService.verifyChain();
    }

    @GetMapping("/verify/status")
    @Operation(summary = "Last scheduled audit chain verification",
            description = "Returns the cached verdict of the last scheduled verification on this instance, "
                    + "without re-walking the chain. Poll this for monitoring; call /verify for an "
                    + "authoritative, on-demand answer. `verified=false` means the chain has not been "
                    + "checked yet on this instance — it does not mean the chain is valid.")
    public Mono<AuditIntegrityStatusResponse> auditIntegrityStatus() {
        return Mono.fromSupplier(() -> new AuditIntegrityStatusResponse(
                integrityStatus.isChainBroken(),
                integrityStatus.lastResult().isPresent(),
                integrityStatus.lastResult().orElse(null),
                integrityStatus.lastFailureMessage().orElse(null),
                integrityStatus.lastFailureAt().orElse(null)));
    }
}
