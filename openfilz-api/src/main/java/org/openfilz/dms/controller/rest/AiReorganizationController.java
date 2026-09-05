package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.ReorganizationApplyRequest;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.request.ReorganizationByKindRequest;
import org.openfilz.dms.service.ai.CategoryReorganizationPlanner;
import org.openfilz.dms.service.ai.ReorganizationPlanService;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
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
import reactor.core.scheduler.Schedulers;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The user's side of an AI-proposed document reorganisation: the chat proposal card reads a plan
 * here and applies or discards it. Plans are created by the AI tools
 * ({@code proposeReorganizationPlan}), never over REST.
 * <p>
 * Same runtime gate as {@link AiChatController}: 404 when {@code openfilz.ai.active} is off. The
 * role gate for applying (CONTRIBUTOR) is enforced in {@link ReorganizationPlanService}, since
 * {@code /api/v1/ai/**} admits every reader.
 * <p>
 * The service is {@code @Lazy} so that nothing AI-related is built when the feature is off, and
 * it is reached through an {@link ObjectProvider} rather than a {@code @Lazy} injection point:
 * that variant wraps a concrete class in a CGLIB lazy-resolution proxy, which the GraalVM native
 * image cannot instantiate ({@code MissingReflectionRegistrationError} on
 * {@code CGLIB$FACTORY_DATA}) — the app then fails to start regardless of {@code ai.active}.
 * {@code ObjectProvider} defers the lookup with no proxy at all, the same way
 * {@link org.openfilz.dms.service.mcp.DocumentAiToolsContributor} reaches its lazy collaborators.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/reorganization")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "AI Chat", description = "AI-powered document chat with RAG and function calling")
public class AiReorganizationController implements UserInfoService {

    private final ObjectProvider<ReorganizationPlanService> planService;
    private final ObjectProvider<CategoryReorganizationPlanner> byKindPlanner;
    private final AiProperties aiProperties;

    public AiReorganizationController(ObjectProvider<ReorganizationPlanService> planService,
                                      ObjectProvider<CategoryReorganizationPlanner> byKindPlanner,
                                      AiProperties aiProperties) {
        this.planService = planService;
        this.byKindPlanner = byKindPlanner;
        this.aiProperties = aiProperties;
    }

    @PostMapping(value = "/by-kind", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Propose a reorganisation by kind of document — no model involved",
            description = "Every folder of the scope holding documents of several kinds (their insight category) gets one "
                    + "sub-folder per kind, named like the existing folders (Invoices / Factures…), and the files move there. "
                    + "The answer is a stored plan to review and apply; a plan without an id means nothing needs splitting.")
    public Mono<ReorganizationPlanView> proposeByKind(@RequestBody(required = false) ReorganizationByKindRequest request) {
        UUID root = request == null ? null : request.rootFolderId();
        return withCaller((service, caller) -> byKindPlanner.getObject().propose(root, null, caller));
    }

    @GetMapping(value = "/{planId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a reorganisation plan proposed by the AI assistant",
            description = "The plan's items (what moves where), which are applicable, and its status.")
    public Mono<ReorganizationPlanView> get(@PathVariable UUID planId) {
        return withCaller((service, caller) -> service.get(planId, caller));
    }

    @PostMapping(value = "/{planId}/apply", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Apply a proposed reorganisation plan",
            description = "Creates the missing folders and moves the selected items (all applicable items when none is given).")
    public Mono<ReorganizationApplyResult> apply(@PathVariable UUID planId,
                                                 @RequestBody(required = false) ReorganizationApplyRequest request) {
        return withCaller((service, caller) -> service.apply(planId, request != null ? request.itemIds() : null, caller));
    }

    @PostMapping(value = "/{planId}/discard", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Discard a proposed reorganisation plan")
    public Mono<ReorganizationPlanView> discard(@PathVariable UUID planId) {
        return withCaller((service, caller) -> service.discard(planId, caller));
    }

    private interface CallerAction<T> {
        T run(ReorganizationPlanService service, Caller caller);
    }

    /** Resolve the caller, then run the blocking service call off the event loop. */
    private <T> Mono<T> withCaller(CallerAction<T> action) {
        if (!aiProperties.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AI feature is disabled");
        }
        // Resolved only past the gate, so the lazy service is first built on the first real call.
        ReorganizationPlanService service = planService.getObject();
        // No-auth deployments have no Authentication at all: the caller is then anonymous, as for smart filing
        return getAuthenticationMono()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> getConnectedUserEmail()
                        .map(email -> new Caller(UserInfoService.ANONYMOUS_USER.equals(email) ? null : email,
                                authentication.orElse(null))))
                .flatMap(caller -> Mono.fromCallable((Callable<T>) () -> action.run(service, caller))
                        .subscribeOn(Schedulers.boundedElastic()));
    }
}
