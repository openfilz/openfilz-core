package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.quota.MyStorageQuota;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The caller's own storage quota — what the dashboard ring and the settings card show.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_QUOTAS)
@RequiredArgsConstructor
@Tag(name = "Quotas", description = "Storage quotas: the caller's usage and effective limits")
@SecurityRequirement(name = "keycloak_auth")
public class QuotaController {

    private final StorageQuotaService storageQuotaService;

    @GetMapping("/me")
    @Operation(summary = "Get my storage quota",
            description = "The caller's used bytes, effective limit (null = unlimited) and where it comes from "
                    + "(USER override, GROUP, DEFAULT), plus the per-file upload limit.")
    public Mono<MyStorageQuota> myQuota() {
        return storageQuotaService.myQuota();
    }
}
