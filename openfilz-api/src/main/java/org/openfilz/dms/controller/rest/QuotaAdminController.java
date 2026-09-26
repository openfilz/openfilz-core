package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.UserQuotaRequest;
import org.openfilz.dms.dto.response.quota.QuotaOverview;
import org.openfilz.dms.dto.response.quota.QuotaUsage;
import org.openfilz.dms.dto.response.quota.QuotaUserPage;
import org.openfilz.dms.service.quota.StorageQuotaService;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * Storage quota administration: the instance picture, every user's usage against their effective
 * limit, and per-user overrides. Reserved to the ADMIN role
 * ({@code AbstractSecurityService#isQuotaAdminAuthorized}). The deployment-wide defaults
 * ({@code openfilz.quota.*}) stay in configuration.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_ADMIN_QUOTAS)
@RequiredArgsConstructor
@Tag(name = "Quota administration", description = "Storage quotas: per-user overrides and usage (ADMIN)")
@SecurityRequirement(name = "keycloak_auth")
public class QuotaAdminController {

    private final StorageQuotaService storageQuotaService;

    @GetMapping
    @Operation(summary = "Quota overview", description = "Deployment-wide limits (MB, 0 = no limit), instance usage and the number of user overrides.")
    public Mono<QuotaOverview> overview() {
        return storageQuotaService.overview();
    }

    @GetMapping("/users")
    @Operation(summary = "List users' storage", description = "Every user storage is charged to, with usage, effective limit and its source. "
            + "Sort by usage (default), percent, limit or name.")
    public Mono<QuotaUserPage> listUsers(
            @Parameter(description = "Case-insensitive filter on the user name") @RequestParam(required = false) String search,
            @Parameter(description = "usage | percent | limit | name") @RequestParam(required = false, defaultValue = "usage") String sort,
            @Parameter(description = "asc | desc") @RequestParam(required = false, defaultValue = "desc") String order,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "25") int size) {
        return storageQuotaService.listUsers(search, sort, !"asc".equalsIgnoreCase(order), page, size);
    }

    @GetMapping("/users/{username}")
    @Operation(summary = "Get one user's storage")
    public Mono<QuotaUsage> getUser(@PathVariable String username) {
        return storageQuotaService.usage(username);
    }

    @PutMapping("/users/{username}")
    @Operation(summary = "Set one user's own limit", description = "quotaMb > 0 = this user's limit, 0 = no limit for this user. "
            + "Takes precedence over group and default limits.")
    public Mono<QuotaUsage> setUserQuota(@PathVariable String username, @RequestBody UserQuotaRequest request) {
        if (request == null || request.quotaMb() == null) {
            return Mono.error(new IllegalArgumentException("quotaMb is required (0 = no limit)"));
        }
        return storageQuotaService.setUserQuota(username, request.quotaMb());
    }

    @DeleteMapping("/users/{username}")
    @Operation(summary = "Remove one user's own limit", description = "The group or default limit applies again.")
    public Mono<QuotaUsage> clearUserQuota(@PathVariable String username) {
        return storageQuotaService.clearUserQuota(username);
    }
}
