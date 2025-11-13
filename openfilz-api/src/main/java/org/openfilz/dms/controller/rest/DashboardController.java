package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DashboardStatisticsResponse;
import org.openfilz.dms.service.DashboardService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for dashboard statistics and metrics
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + "/dashboard")
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Dashboard", description = "Dashboard statistics and metrics")
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Get dashboard statistics including file counts, storage usage, and breakdowns
     *
     * @return Dashboard statistics
     */
    @GetMapping(value = "/statistics", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get dashboard statistics",
            description = "Retrieve aggregated dashboard metrics including file counts, storage usage, and file type distribution"
    )
    public Mono<DashboardStatisticsResponse> getDashboardStatistics() {
        log.info("Fetching dashboard statistics");
        return dashboardService.getDashboardStatistics()
                .doOnSuccess(stats -> log.debug("Successfully retrieved dashboard statistics"))
                .doOnError(error -> log.error("Error retrieving dashboard statistics", error));
    }
}
