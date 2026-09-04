package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.SaveAiPreferencesRequest;
import org.openfilz.dms.dto.response.AiPreferencesView;
import org.openfilz.dms.service.filing.AiPreferencesService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * The user's own AI preferences that need no API key: the smart-filing switch remembered from
 * the upload area. Self-scoped on the caller's email, like the BYOK settings next door.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai/preferences")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Settings", description = "Openfilz global settings and User preferences")
public class AiPreferencesController implements UserInfoService {

    private final AiPreferencesService preferencesService;
    private final AiProperties aiProperties;

    public AiPreferencesController(@Lazy AiPreferencesService preferencesService, AiProperties aiProperties) {
        this.preferencesService = preferencesService;
        this.aiProperties = aiProperties;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The caller's smart-filing preferences")
    public Mono<AiPreferencesView> get() {
        return email().flatMap(preferencesService::get).map(p -> preferencesService.view(p, autoFileAvailable()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save the caller's smart-filing preferences", description = "A null field leaves the current value unchanged.")
    public Mono<AiPreferencesView> save(@RequestBody SaveAiPreferencesRequest request) {
        return email().flatMap(email -> preferencesService.save(email, request))
                .map(p -> preferencesService.view(p, autoFileAvailable()));
    }

    private boolean autoFileAvailable() {
        return aiProperties.isActive() && aiProperties.getAutoFile().isActive();
    }

    private Mono<String> email() {
        return getConnectedUserEmail();
    }
}
