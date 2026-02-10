package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import org.openfilz.dms.service.OnlyOfficeService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * REST controller for OnlyOffice DocumentServer integration.
 * Provides endpoints for editor configuration and document callbacks.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + "/onlyoffice")
@RequiredArgsConstructor
@Tag(name = "OnlyOffice", description = "OnlyOffice Document Editor integration endpoints")
@ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true")
public class OnlyOfficeController implements UserInfoService {

    private final OnlyOfficeService onlyOfficeService;

    /**
     * Get the configuration needed to initialize the OnlyOffice editor.
     *
     * @param documentId The document ID to edit
     * @param canEdit    Whether the user can edit the document (default: true)
     * @return Editor configuration including JWT token for OnlyOffice
     */
    @GetMapping(value = "/config/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Get OnlyOffice editor configuration",
            description = "Generates the configuration object needed to initialize the OnlyOffice editor, including the JWT token for authentication."
    )
    public Mono<OnlyOfficeConfigResponse> getEditorConfig(
            @PathVariable UUID documentId,
            @Parameter(description = "Whether the user can edit the document")
            @RequestParam(defaultValue = "true") boolean canEdit) {

        log.debug("Generating OnlyOffice config for document {}, canEdit={}", documentId, canEdit);

        return onlyOfficeService.generateEditorConfig(documentId, canEdit);
    }

    /**
     * Handle callback from OnlyOffice DocumentServer.
     * This endpoint is called by OnlyOffice when document state changes (editing, saved, closed, etc.).
     *
     * @param documentId The document ID
     * @param callback   The callback request from OnlyOffice
     * @return Response indicating success (error: 0)
     */
    @PostMapping(value = "/callback/{documentId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "OnlyOffice callback endpoint",
            description = "Receives document save events from OnlyOffice DocumentServer. Called automatically by OnlyOffice when documents are saved or closed."
    )
    public Mono<Map<String, Integer>> handleCallback(
            @PathVariable UUID documentId,
            @RequestBody OnlyOfficeCallbackRequest callback) {

        log.info("Received OnlyOffice callback for document {}: status={}, key={}",
                documentId, callback.status(), callback.key());

        return onlyOfficeService.handleCallback(documentId, callback)
                .thenReturn(Map.of("error", 0))
                .onErrorResume(e -> {
                    log.error("Error processing OnlyOffice callback for document {}: {}", documentId, e.getMessage());
                    // Return error code 1 to indicate failure
                    return Mono.just(Map.of("error", 1));
                });
    }

    /**
     * Check if OnlyOffice integration is enabled.
     *
     * @return Status object with enabled flag
     */
    @GetMapping(value = "/status", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Check OnlyOffice status",
            description = "Returns whether OnlyOffice integration is enabled"
    )
    public Mono<Map<String, Object>> getStatus() {
        return Mono.just(Map.of(
                "enabled", onlyOfficeService.isEnabled(),
                "status", "ok"
        ));
    }

    /**
     * Check if a file is supported by OnlyOffice.
     *
     * @param fileName The file name to check
     * @return Whether the file type is supported
     */
    @GetMapping(value = "/supported", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
            summary = "Check if file type is supported",
            description = "Returns whether a file type is supported for OnlyOffice editing"
    )
    public Mono<Map<String, Object>> isSupported(
            @Parameter(description = "File name to check") @RequestParam String fileName) {
        return Mono.just(Map.of(
                "fileName", fileName,
                "supported", onlyOfficeService.isSupported(fileName)
        ));
    }

}
