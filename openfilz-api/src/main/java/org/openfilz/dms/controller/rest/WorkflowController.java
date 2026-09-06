package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.CompleteTaskRequest;
import org.openfilz.dms.dto.workflow.MyTasksCountDTO;
import org.openfilz.dms.dto.workflow.ReassignTaskRequest;
import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstancePage;
import org.openfilz.dms.dto.workflow.WorkflowSummaryDTO;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.dto.workflow.WorkflowValidationResult;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.service.workflow.WorkflowDefinitionService;
import org.openfilz.dms.service.workflow.WorkflowRoles;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Workflows (docs/workflows.md §6). Always mapped; every call gates on
 * {@code openfilz.workflows.active} at runtime (404 when off) so the toggle works in native images.
 */
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_WORKFLOWS)
@RequiredArgsConstructor
@Tag(name = "Workflows", description = "Statuses, transitions and tasks on documents.")
public class WorkflowController implements UserInfoService {

    private final WorkflowDefinitionService definitionService;
    private final WorkflowService workflowService;
    private final WorkflowProperties props;
    private final WorkflowRoles roles;

    // ── definitions ───────────────────────────────────────────────────────

    @GetMapping(value = "/definitions", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List workflow definitions")
    public Flux<WorkflowDefinitionDTO> listDefinitions(@RequestParam(required = false) Boolean active) {
        requireActive();
        return definitionService.list(active);
    }

    @GetMapping(value = "/definitions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Get a workflow definition")
    public Mono<WorkflowDefinitionDTO> getDefinition(@PathVariable UUID id) {
        requireActive();
        return definitionService.get(id);
    }

    @PostMapping(value = "/definitions", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a workflow definition (400 with the problem list when invalid)")
    public Mono<WorkflowDefinitionDTO> createDefinition(@Valid @RequestBody SaveWorkflowDefinitionRequest req) {
        return email().flatMap(e -> definitionService.create(req, e));
    }

    @PutMapping(value = "/definitions/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Update a workflow definition (running instances keep their snapshot)")
    public Mono<WorkflowDefinitionDTO> updateDefinition(@PathVariable UUID id, @Valid @RequestBody SaveWorkflowDefinitionRequest req) {
        return email().flatMap(e -> definitionService.update(id, req, e));
    }

    @DeleteMapping("/definitions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a workflow definition (409 while instances are running)")
    public Mono<Void> deleteDefinition(@PathVariable UUID id) {
        return email().flatMap(e -> definitionService.delete(id, e));
    }

    @PostMapping(value = "/definitions/validate", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Validate a definition without saving it")
    // No @Valid: a dry run reports problems instead of refusing them, and the 404 must win when the feature is off.
    public Mono<WorkflowValidationResult> validateDefinition(@RequestBody SaveWorkflowDefinitionRequest req) {
        requireActive();
        return Mono.fromSupplier(() -> definitionService.validate(req));
    }

    // ── instances ─────────────────────────────────────────────────────────

    @PostMapping(value = "/instances", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start a workflow on a document (409 when one is already running)")
    public Mono<WorkflowInstanceDTO> start(@Valid @RequestBody StartWorkflowRequest req,
                                           @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return actor(acceptLanguage).flatMap(a -> workflowService.start(req, a));
    }

    @GetMapping(value = "/instances", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List instances (monitor), newest first")
    public Mono<WorkflowInstancePage> listInstances(@RequestParam(required = false) UUID documentId,
                                                    @RequestParam(required = false) UUID definitionId,
                                                    @RequestParam(required = false) WorkflowInstanceStatus status,
                                                    @RequestParam(required = false) String state,
                                                    @RequestParam(required = false, defaultValue = "false") boolean mine,
                                                    @RequestParam(required = false, defaultValue = "0") int page,
                                                    @RequestParam(required = false, defaultValue = "25") int size) {
        return actor(null).flatMap(a -> workflowService.list(documentId, definitionId, status, state, mine, page, size, a));
    }

    @GetMapping(value = "/instances/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Counts for the monitor header")
    public Mono<WorkflowSummaryDTO> summary() {
        return actor(null).flatMap(workflowService::summary);
    }

    @GetMapping(value = "/instances/{id}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "One instance with its snapshot spec, open task and history")
    public Mono<WorkflowInstanceDetailDTO> getInstance(@PathVariable UUID id) {
        return actor(null).flatMap(a -> workflowService.get(id, a));
    }

    @PostMapping(value = "/instances/{id}/cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Cancel a running instance (initiator)")
    public Mono<WorkflowInstanceDTO> cancel(@PathVariable UUID id, @RequestBody(required = false) CancelInstanceRequest req) {
        return actor(null).flatMap(a -> workflowService.cancel(id, req, a));
    }

    // ── tasks ─────────────────────────────────────────────────────────────

    @GetMapping(value = "/tasks/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Open tasks I can act on (by e-mail or role)")
    public Flux<WorkflowTaskDTO> myTasks() {
        return actor(null).flatMapMany(workflowService::myTasks);
    }

    @GetMapping(value = "/tasks/mine/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Number of open tasks I can act on (sidebar badge)")
    public Mono<MyTasksCountDTO> myTasksCount() {
        return actor(null).flatMap(workflowService::myTasksCount);
    }

    @PostMapping(value = "/tasks/{id}/complete", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Take a transition on a task I am a candidate of")
    public Mono<WorkflowInstanceDTO> complete(@PathVariable UUID id, @Valid @RequestBody CompleteTaskRequest req) {
        return actor(null).flatMap(a -> workflowService.complete(id, req, a));
    }

    @PostMapping(value = "/tasks/{id}/reassign", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Hand a task to other people (initiator or current candidate)")
    public Mono<WorkflowInstanceDTO> reassign(@PathVariable UUID id, @Valid @RequestBody ReassignTaskRequest req) {
        return actor(null).flatMap(a -> workflowService.reassign(id, req, a));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void requireActive() {
        if (!props.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Workflows feature is disabled");
        }
    }

    private Mono<String> email() {
        requireActive();
        return getAuthenticationMono()
                .map(this::emailOf)
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")));
    }

    private Mono<WorkflowService.Actor> actor(String acceptLanguage) {
        requireActive();
        return getAuthenticationMono()
                .map(auth -> new WorkflowService.Actor(emailOf(auth), roles.of(auth), auth, acceptLanguage))
                .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated")));
    }

    private String emailOf(org.springframework.security.core.Authentication auth) {
        String email = getUserAttribute(auth, "email");
        if (email == null || email.isBlank() || ANONYMOUS_USER.equals(email)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "The token has no email claim");
        }
        return email.toLowerCase();
    }
}
