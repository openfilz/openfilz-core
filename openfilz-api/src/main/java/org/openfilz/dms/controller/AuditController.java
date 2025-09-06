package org.openfilz.dms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.service.AuditService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.UUID;

@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + "/audit")
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class AuditController {
    private final AuditService auditService;

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
}
