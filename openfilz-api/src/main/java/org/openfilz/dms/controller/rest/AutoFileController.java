package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.AutoFileRequest;
import org.openfilz.dms.dto.response.AutoFileJobView;
import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.openfilz.dms.service.filing.AutoFileService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/**
 * The user's side of smart filing: follow the job an upload started, undo it, file existing
 * documents on demand, and read a document's filing record. 404 when the feature is off.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_AI + "/auto-file")
@SecurityRequirement(name = "keycloak_auth")
@Tag(name = "Smart filing", description = "OpenFilz chooses the destination folder of uploaded documents on request")
public class AutoFileController implements UserInfoService {

    private final AutoFileService autoFileService;

    public AutoFileController(@Lazy AutoFileService autoFileService) {
        this.autoFileService = autoFileService;
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "File existing documents on demand",
            description = "Runs smart filing for the given documents (e.g. the contents of an Inbox) and returns the job at once.")
    public Mono<AutoFileJobView> file(@RequestBody AutoFileRequest request) {
        requireActive();
        List<UUID> ids = request == null || request.documentIds() == null ? List.of() : request.documentIds();
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "documentIds is required");
        }
        return withCaller(caller -> autoFileService.schedule(ids, caller, request.allowNewFolders()));
    }

    @GetMapping(value = "/{jobId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Progress and outcome of a filing job")
    public Mono<AutoFileJobView> job(@PathVariable UUID jobId) {
        requireActive();
        return withCaller(caller -> autoFileService.job(jobId, caller)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown filing job")));
    }

    @PostMapping(value = "/{jobId}/undo", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Move every document the job filed back where it came from")
    public Mono<AutoFileJobView> undo(@PathVariable UUID jobId) {
        requireActive();
        return withCaller(caller -> {
            try {
                return autoFileService.undo(jobId, caller);
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
            }
        });
    }

    @GetMapping(value = "/document/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "The latest filing record of a document (the \"Filed by OpenFilz\" chip)")
    public Mono<FilingOutcome> lastFiling(@PathVariable UUID documentId) {
        requireActive();
        return callerMono().flatMap(caller -> autoFileService.lastFiling(documentId, caller))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "No filing record")));
    }

    @PostMapping(value = "/filing/{planId}/undo", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Move one filed document back (the chip's \"Move back\")")
    public Mono<FilingOutcome> undoFiling(@PathVariable UUID planId) {
        requireActive();
        return callerMono().flatMap(caller -> autoFileService.undoFiling(planId, caller))
                .onErrorMap(IllegalArgumentException.class, e -> new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage()));
    }

    private void requireActive() {
        if (!autoFileService.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Smart filing is disabled");
        }
    }

    private Mono<Caller> callerMono() {
        return getAuthenticationMono()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(authentication -> getConnectedUserEmail()
                        .map(email -> new Caller(UserInfoService.ANONYMOUS_USER.equals(email) ? null : email,
                                authentication.orElse(null))));
    }

    private interface CallerAction<T> {
        T run(Caller caller);
    }

    /** Resolve the caller, then run the blocking service call off the event loop. */
    private <T> Mono<T> withCaller(CallerAction<T> action) {
        return callerMono().flatMap(caller -> Mono.fromCallable((Callable<T>) () -> action.run(caller))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    @SuppressWarnings("unused")
    private static Authentication none() {
        return null;
    }
}
