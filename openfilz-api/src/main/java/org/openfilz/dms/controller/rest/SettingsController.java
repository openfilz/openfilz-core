package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.service.SettingsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * REST controller for dashboard statistics and metrics
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS)
@RequiredArgsConstructor
@Tag(name = "Settings", description = "Openfilz global settings and User preferences")
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class SettingsController {

    private final SettingsService settingsService;

    /**
     * Get dashboard statistics including file counts, storage usage, and breakdowns
     *
     * @return Dashboard statistics
     */
    @GetMapping
    @Operation(
            summary = "Get user's settings",
            description = "Retrieve user's settings : global settings and user's preferences"
    )
    public Mono<Settings> getSettings() {
        log.info("Fetching user settings");
        return settingsService.getSettings()
                .doOnSuccess(stats -> log.debug("Successfully retrieved dashboard statistics"))
                .doOnError(error -> log.error("Error retrieving dashboard statistics", error));
    }
}
