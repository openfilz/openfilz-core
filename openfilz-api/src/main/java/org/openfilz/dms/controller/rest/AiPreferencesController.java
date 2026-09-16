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
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * The user's own AI preferences that need no API key: the smart-filing switch remembered from
 * the upload area, and their optional Inbox folder. Self-scoped on the caller's email, like the
 * BYOK settings next door.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai/preferences")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Settings", description = "Openfilz global settings and User preferences")
public class AiPreferencesController implements UserInfoService {

    // ObjectProvider, not @Lazy: AiPreferencesService is a concrete class with no interface, so a
    // @Lazy injection point makes Spring inject a CGLIB lazy-resolution proxy, whose
    // $$SpringCGLIB$$0 class carries no reflection metadata in a native image — the container
    // dies at boot with MissingReflectionRegistrationError on CGLIB$FACTORY_DATA. ObjectProvider
    // defers creation the same way with no proxy at all. @Lazy stays fine where the injected type
    // is an interface (JDK proxy, hinted by Spring AOT).
    private final ObjectProvider<AiPreferencesService> preferencesServiceProvider;
    private final AiProperties aiProperties;

    public AiPreferencesController(ObjectProvider<AiPreferencesService> preferencesServiceProvider,
                                   AiProperties aiProperties) {
        this.preferencesServiceProvider = preferencesServiceProvider;
        this.aiProperties = aiProperties;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The caller's smart-filing preferences")
    public Mono<AiPreferencesView> get() {
        AiPreferencesService preferencesService = preferencesServiceProvider.getObject();
        return email().flatMap(preferencesService::get).flatMap(p -> preferencesService.view(p, autoFileAvailable()));
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save the caller's smart-filing preferences",
            description = "A null field leaves the current value unchanged. inbox=true creates the caller's Inbox folder "
                    + "at their root on demand (named from Accept-Language, else the deployment's default language); "
                    + "inbox=false forgets it without deleting the folder. 400 when the Inbox is not offered.")
    public Mono<AiPreferencesView> save(@RequestBody SaveAiPreferencesRequest request,
                                        @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        AiPreferencesService preferencesService = preferencesServiceProvider.getObject();
        return email().flatMap(email -> preferencesService.save(email, request, acceptLanguage))
                .onErrorMap(IllegalStateException.class, e -> new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()))
                .flatMap(p -> preferencesService.view(p, autoFileAvailable()));
    }

    /** The single gate of the whole preferences surface: smart filing must run on this deployment. */
    private boolean autoFileAvailable() {
        return aiProperties.isActive() && aiProperties.getAutoFile().isActive();
    }

    private Mono<String> email() {
        return getConnectedUserEmail();
    }
}
