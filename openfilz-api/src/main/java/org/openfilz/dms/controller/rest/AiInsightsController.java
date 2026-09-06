package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.InsightBackfillRequest;
import org.openfilz.dms.dto.response.InsightBackfillStatus;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.insight.DocumentInsightService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Operator side of the tier-2 document insights: enrich existing documents (backfill) and
 * follow the job. 404 when insights are off; starting a backfill needs the CONTRIBUTOR role
 * (the {@code /api/v1/ai/**} security rule admits every reader, so the gate is explicit here).
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/insights")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Document Insights", description = "File metadata and AI-derived category / summary of a document")
public class AiInsightsController implements UserInfoService {

    private final DocumentInsightService insightService;
    private final AiToolRolePolicy rolePolicy;

    public AiInsightsController(@Lazy DocumentInsightService insightService, AiToolRolePolicy rolePolicy) {
        this.insightService = insightService;
        this.rolePolicy = rolePolicy;
    }

    @PostMapping(value = "/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Enrich existing documents",
            description = "Queues every FILE without a tier-2 insight (or with an older prompt version when force), "
                    + "optionally under one folder, and returns the job at once. Poll GET /backfill/{jobId}.")
    public Mono<InsightBackfillStatus> backfill(@RequestBody(required = false) InsightBackfillRequest request) {
        requireActive();
        UUID folderId = request == null ? null : request.folderId();
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        // No Authentication at all means authorisation is off on this deployment (no-auth mode),
        // which the role policy already treats as permitted — the same convention as the tools.
        return getAuthenticationMono()
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(authentication -> {
                    if (!rolePolicy.isAllowed(authentication.orElse(null), ToolCapability.DOCUMENT_WRITE)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Backfilling document insights needs the CONTRIBUTOR role"));
                    }
                    return getConnectedUserEmail().flatMap(email -> insightService.backfill(folderId, force, email));
                });
    }

    @GetMapping(value = "/backfill/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Progress of a backfill job")
    public Mono<InsightBackfillStatus> backfillStatus(@PathVariable UUID jobId) {
        requireActive();
        return Mono.justOrEmpty(insightService.backfillStatus(jobId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown backfill job")));
    }

    private void requireActive() {
        if (!insightService.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document insights are disabled");
        }
    }
}
