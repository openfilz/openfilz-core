package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.service.AiSettingsService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * REST controller for the connected user's personal chat-LLM settings (BYOK).
 * The API key is write-only: it is accepted on PUT/test and never returned.
 * <p>
 * Always mapped; each endpoint gates on {@code openfilz.ai.active} at runtime (404 when off)
 * because bean conditions are build-time in GraalVM native images.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Settings", description = "Per-user AI model settings (bring your own key)")
public class AiSettingsController implements UserInfoService {

    private final AiSettingsService aiSettingsService;
    private final AiProperties aiProperties;

    public AiSettingsController(@Lazy AiSettingsService aiSettingsService, AiProperties aiProperties) {
        this.aiSettingsService = aiSettingsService;
        this.aiProperties = aiProperties;
    }

    /**
     * 404 when the AI feature is disabled at runtime — same shape as when it wasn't deployed.
     * <p>
     * BYOK only ever overrides the <em>chat</em> model, so it follows the chat kill switch
     * ({@code openfilz.ai.chat.active}) as well: on a deployment that runs the automatic AI
     * features without a chat model there is nothing here for a user to configure.
     */
    private void requireAiActive() {
        if (!aiProperties.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI feature is disabled");
        }
        if (!aiProperties.getChat().isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI chat assistant is disabled");
        }
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get my AI settings",
            description = "Current user's chat-LLM override. The API key is never returned — only hasApiKey and its last characters.")
    public Mono<AiSettingsResponse> getSettings() {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMap(aiSettingsService::getSettings);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save my AI settings",
            description = "Set the chat LLM to use for my conversations. Omit apiKey to keep the previously stored key.")
    public Mono<AiSettingsResponse> saveSettings(@Valid @RequestBody SaveAiSettingsRequest request) {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMap(email -> aiSettingsService.saveSettings(email, request));
    }

    @DeleteMapping
    @Operation(summary = "Reset my AI settings",
            description = "Remove my chat-LLM override and go back to the server default model.")
    public Mono<Void> deleteSettings() {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMap(aiSettingsService::deleteSettings);
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Test AI provider connection",
            description = "Send a minimal completion request with the submitted settings (falls back to the stored key when apiKey is omitted).")
    public Mono<AiConnectionTestResult> testConnection(@Valid @RequestBody SaveAiSettingsRequest request) {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMap(email -> aiSettingsService.testConnection(email, request));
    }

    @PostMapping(value = "/models", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List the provider's chat models",
            description = "Ask the provider which chat models this key can use, so the picker "
                    + "reflects what exists today rather than a list baked into a release. Falls "
                    + "back to a built-in list (source=FALLBACK) when the provider cannot be "
                    + "reached. POST rather than GET because the key travels in the body, never "
                    + "in a query string; omit apiKey to use the stored key.")
    public Mono<AiModelsResponse> listModels(@Valid @RequestBody ListAiModelsRequest request) {
        requireAiActive();
        return getConnectedUserEmail()
                .flatMap(email -> aiSettingsService.listModels(email, request));
    }
}
