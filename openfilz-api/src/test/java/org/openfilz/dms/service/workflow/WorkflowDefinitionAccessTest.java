package org.openfilz.dms.service.workflow;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.dto.workflow.SaveWorkflowDefinitionRequest;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowProblem;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.entity.WorkflowDefinition;
import org.openfilz.dms.enums.WorkflowTransitionStyle;
import org.openfilz.dms.enums.WorkflowActionType;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.exception.WorkflowValidationException;
import org.openfilz.dms.repository.WorkflowDefinitionRepository;
import org.openfilz.dms.repository.WorkflowInstanceRepository;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.openfilz.dms.service.workflow.impl.WorkflowDefinitionServiceImpl;
import org.openfilz.dms.utils.WorkflowJson;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who may change a definition, and which folders it may point at — the API's own checks, not the
 * designer's UI.
 * <p>
 * A definition may only point at folders its author can write into — the API's own check, not the
 * designer's folder picker. Core allows every folder ({@code canUseFolder} defaults to true); this
 * pins that the service asks, refuses on the right path, and never saves a definition it refused.
 */
class WorkflowDefinitionAccessTest {

    private static final String USER = "alice@example.com";
    private static final WorkflowService.Actor ACTOR =
            new WorkflowService.Actor(USER, List.of("CONTRIBUTOR"), (Authentication) null, null);
    private static final UUID ALLOWED = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID REFUSED = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEFINITION = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private final WorkflowDefinitionRepository repo = mock(WorkflowDefinitionRepository.class);
    private final WorkflowInstanceRepository instances = mock(WorkflowInstanceRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final WorkflowAccessPolicy accessPolicy = mock(WorkflowAccessPolicy.class);
    private final TransactionalOperator tx = mock(TransactionalOperator.class);

    private WorkflowDefinitionServiceImpl service;

    @BeforeEach
    void setUp() {
        WorkflowProperties props = new WorkflowProperties();
        props.setMaxStates(30);
        service = new WorkflowDefinitionServiceImpl(repo, instances, props, auditService, accessPolicy, tx);
        when(tx.transactional(any(Mono.class))).thenAnswer(i -> i.getArgument(0));
        when(accessPolicy.canUseFolder(eq(ALLOWED), anyString())).thenReturn(Mono.just(true));
        when(accessPolicy.canUseFolder(eq(REFUSED), anyString())).thenReturn(Mono.just(false));
        when(accessPolicy.canEditDefinition(any(), anyString(), any())).thenReturn(Mono.just(true));
        // The happy path is assembled even when the folder check refuses (the operators are built
        // eagerly, subscribed lazily), so these have to answer in every case.
        when(repo.findByNameIgnoreCase(anyString())).thenReturn(Mono.empty());
        when(repo.save(any())).thenAnswer(i -> Mono.just(i.<WorkflowDefinition>getArgument(0)));
        when(auditService.logAction(any(), any(), any())).thenReturn(Mono.empty());
        when(instances.countByDefinitionIdAndStatus(any(), any())).thenReturn(Mono.just(0L));
    }

    /** Minimal valid spec: START → END, with an optional MOVE_TO_FOLDER on the final status. */
    private static WorkflowSpec spec(UUID moveTo) {
        List<WorkflowAction> onEnter = moveTo == null ? List.of()
                : List.of(new WorkflowAction(WorkflowActionType.MOVE_TO_FOLDER, moveTo, null, null));
        return new WorkflowSpec(List.of(
                new WorkflowState("draft", "Draft", WorkflowStateKind.START, "#94a3b8", WorkflowAssignment.initiator(), null,
                        List.of(new WorkflowTransition("done", "Done", "done", WorkflowTransitionStyle.SUCCESS, false)), List.of()),
                new WorkflowState("done", "Done", WorkflowStateKind.END, "#10b981", null, null, List.of(), onEnter)));
    }

    private static SaveWorkflowDefinitionRequest request(UUID moveTo, List<UUID> triggers) {
        return new SaveWorkflowDefinitionRequest("Approval", null, true, spec(moveTo), triggers);
    }

    @Test
    @DisplayName("a hot folder the designer cannot write into is refused, on its own path")
    void refusesAnUnwritableTriggerFolder() {
        StepVerifier.create(service.create(request(null, List.of(REFUSED)), ACTOR))
                .verifyErrorSatisfies(e -> {
                    assertThat(e).isInstanceOf(WorkflowValidationException.class);
                    List<WorkflowProblem> problems = ((WorkflowValidationException) e).getProblems();
                    assertThat(problems).singleElement().satisfies(p -> {
                        assertThat(p.code()).isEqualTo("FOLDER_NOT_WRITABLE");
                        assertThat(p.path()).isEqualTo("triggerFolderIds[0]");
                        assertThat(p.args()).containsExactly(REFUSED.toString());
                    });
                });

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("a MOVE_TO_FOLDER destination is checked too, and named by its action path")
    void refusesAnUnwritableMoveDestination() {
        StepVerifier.create(service.create(request(REFUSED, List.of()), ACTOR))
                .verifyErrorSatisfies(e -> assertThat(((WorkflowValidationException) e).getProblems())
                        .singleElement()
                        .satisfies(p -> {
                            assertThat(p.code()).isEqualTo("FOLDER_NOT_WRITABLE");
                            // the path the designer highlights on the offending status card
                            assertThat(p.path()).isEqualTo("states[1].onEnter[0].folderId");
                        }));

        verify(repo, never()).save(any());
    }

    @Test
    @DisplayName("every offending folder is reported at once, not one per round-trip")
    void reportsEveryRefusedFolderTogether() {
        UUID otherRefused = UUID.fromString("33333333-3333-3333-3333-333333333333");
        when(accessPolicy.canUseFolder(eq(otherRefused), anyString())).thenReturn(Mono.just(false));

        StepVerifier.create(service.create(request(otherRefused, List.of(REFUSED)), ACTOR))
                .verifyErrorSatisfies(e -> assertThat(((WorkflowValidationException) e).getProblems())
                        .hasSize(2)
                        .extracting(WorkflowProblem::path)
                        .containsExactly("triggerFolderIds[0]", "states[1].onEnter[0].folderId"));
    }

    @Test
    @DisplayName("writable folders go through — the check is a gate, not a wall")
    void acceptsWritableFolders() {
        StepVerifier.create(service.create(request(ALLOWED, List.of(ALLOWED)), ACTOR))
                .assertNext(dto -> assertThat(dto.name()).isEqualTo("Approval"))
                .verifyComplete();

        verify(repo).save(any());
    }

    @Test
    @DisplayName("changing someone else's definition is refused with 403, and nothing is written")
    void refusesToChangeADefinitionTheCallerDoesNotOwn() {
        WorkflowDefinition theirs = WorkflowDefinition.builder().id(DEFINITION).name("Approval")
                .createdBy("bob@example.com").version(1).build();
        when(repo.findById(DEFINITION)).thenReturn(Mono.just(theirs));
        when(accessPolicy.canEditDefinition(any(), anyString(), any())).thenReturn(Mono.just(false));

        StepVerifier.create(service.update(DEFINITION, request(null, List.of()), ACTOR))
                .verifyErrorSatisfies(e -> assertThat(e)
                        .isInstanceOf(ResponseStatusException.class)
                        .extracting(x -> ((ResponseStatusException) x).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        StepVerifier.create(service.delete(DEFINITION, ACTOR))
                .verifyErrorSatisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));

        verify(repo, never()).save(any());
        verify(repo, never()).delete(any());
    }

    @Test
    @DisplayName("the DTO tells the designer whether this caller may change the definition")
    void dtoCarriesTheEditVerdict() {
        WorkflowDefinition theirs = WorkflowDefinition.builder().id(DEFINITION).name("Approval")
                .createdBy("bob@example.com").version(1)
                .spec(WorkflowJson.toJson(spec(null))).build();
        when(repo.findById(DEFINITION)).thenReturn(Mono.just(theirs));
        when(accessPolicy.canEditDefinition(any(), anyString(), any())).thenReturn(Mono.just(false));

        StepVerifier.create(service.get(DEFINITION, ACTOR))
                .assertNext(dto -> {
                    assertThat(dto.canEdit()).isFalse();
                    assertThat(dto.createdBy()).isEqualTo("bob@example.com");
                })
                .verifyComplete();
    }

    @Test
    @DisplayName("a definition with no folder at all asks the policy nothing")
    void skipsTheCheckWhenNoFolderIsInvolved() {
        StepVerifier.create(service.create(request(null, List.of()), ACTOR)).expectNextCount(1).verifyComplete();

        verify(accessPolicy, never()).canUseFolder(any(), anyString());
    }
}
