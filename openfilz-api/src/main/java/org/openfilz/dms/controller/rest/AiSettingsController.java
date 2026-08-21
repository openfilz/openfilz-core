package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import org.openfilz.dms.service.AiSettingsService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

/**
 * REST controller for the connected user's personal chat-LLM settings (BYOK).
 * The API key is write-only: it is accepted on PUT/test and never returned.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SETTINGS + "/ai")
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Settings", description = "Per-user AI model settings (bring your own key)")
@ConditionalOnProperty(name = "openfilz.ai.active", havingValue = "true")
public class AiSettingsController implements UserInfoService {

    private final AiSettingsService aiSettingsService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get my AI settings",
            description = "Current user's chat-LLM override. The API key is never returned — only hasApiKey and its last characters.")
    public Mono<AiSettingsResponse> getSettings() {
        return getConnectedUserEmail()
                .flatMap(aiSettingsService::getSettings);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Save my AI settings",
            description = "Set the chat LLM to use for my conversations. Omit apiKey to keep the previously stored key.")
    public Mono<AiSettingsResponse> saveSettings(@Valid @RequestBody SaveAiSettingsRequest request) {
        return getConnectedUserEmail()
                .flatMap(email -> aiSettingsService.saveSettings(email, request));
    }

    @DeleteMapping
    @Operation(summary = "Reset my AI settings",
            description = "Remove my chat-LLM override and go back to the server default model.")
    public Mono<Void> deleteSettings() {
        return getConnectedUserEmail()
                .flatMap(aiSettingsService::deleteSettings);
    }

    @PostMapping(value = "/test", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Test AI provider connection",
            description = "Send a minimal completion request with the submitted settings (falls back to the stored key when apiKey is omitted).")
    public Mono<AiConnectionTestResult> testConnection(@Valid @RequestBody SaveAiSettingsRequest request) {
        return getConnectedUserEmail()
                .flatMap(email -> aiSettingsService.testConnection(email, request));
    }
}
