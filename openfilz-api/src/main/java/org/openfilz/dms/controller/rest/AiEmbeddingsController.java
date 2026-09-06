package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.EmbeddingBackfillRequest;
import org.openfilz.dms.dto.response.EmbeddingBackfillStatus;
import org.openfilz.dms.service.ai.AiToolRolePolicy;
import org.openfilz.dms.service.ai.ToolCapability;
import org.openfilz.dms.service.impl.EmbeddingBackfillService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.Optional;
import java.util.UUID;

/**
 * Operator side of the vector store: embed existing documents (backfill) and follow the job —
 * what a provider switch needs after the store was wiped, and what repairs a document whose
 * embedding failed at upload. 404 when AI is off (a runtime check, never a bean condition);
 * starting a backfill needs the CONTRIBUTOR role, gated here because the {@code /api/v1/ai/**}
 * security rule admits every reader.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/embeddings")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Embeddings", description = "Embed existing documents into the vector store")
public class AiEmbeddingsController implements UserInfoService {

    private final AiProperties aiProperties;
    // ObjectProvider, not @Lazy: the service is a concrete class, and a @Lazy injection point
    // would be a CGLIB proxy with no reflection metadata in the native image.
    private final ObjectProvider<EmbeddingBackfillService> backfillProvider;
    private final AiToolRolePolicy rolePolicy;

    public AiEmbeddingsController(AiProperties aiProperties, ObjectProvider<EmbeddingBackfillService> backfillProvider,
                                  AiToolRolePolicy rolePolicy) {
        this.aiProperties = aiProperties;
        this.backfillProvider = backfillProvider;
        this.rolePolicy = rolePolicy;
    }

    @PostMapping(value = "/backfill", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Embed existing documents",
            description = "Queues every FILE without a chunk in the vector store (or every FILE when force), optionally "
                    + "under one folder, and returns the job at once. Poll GET /backfill/{jobId}. Run it after wiping the "
                    + "vector store for an embedding-provider switch.")
    public Mono<EmbeddingBackfillStatus> backfill(@RequestBody(required = false) EmbeddingBackfillRequest request) {
        EmbeddingBackfillService service = requireActive();
        UUID folderId = request == null ? null : request.folderId();
        boolean force = request != null && Boolean.TRUE.equals(request.force());
        // No Authentication at all means authorisation is off on this deployment (no-auth mode),
        // which the role policy already treats as permitted — the same convention as the tools.
        return getAuthenticationMono()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> {
                    if (!rolePolicy.isAllowed(authentication.orElse(null), ToolCapability.DOCUMENT_WRITE)) {
                        return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                                "Backfilling document embeddings needs the CONTRIBUTOR role"));
                    }
                    return service.backfill(folderId, force);
                });
    }

    @GetMapping(value = "/backfill/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Progress of an embedding backfill job")
    public Mono<EmbeddingBackfillStatus> backfillStatus(@PathVariable UUID jobId) {
        EmbeddingBackfillService service = requireActive();
        return Mono.justOrEmpty(service.backfillStatus(jobId))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown backfill job")));
    }

    private EmbeddingBackfillService requireActive() {
        EmbeddingBackfillService service = aiProperties.isActive() ? backfillProvider.getIfAvailable() : null;
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "The AI feature is disabled");
        }
        return service;
    }
}
