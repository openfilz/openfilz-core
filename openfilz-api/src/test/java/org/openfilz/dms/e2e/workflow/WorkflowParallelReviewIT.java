package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowEventDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowReview;
import org.openfilz.dms.dto.workflow.WorkflowReviewProgressDTO;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowEventType;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.enums.WorkflowReviewRule;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.enums.WorkflowTransitionStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** Parallel review steps: one task per reviewer, votes with comments, and the three rules that combine them. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowParallelReviewIT extends AbstractWorkflowIT {

    WorkflowParallelReviewIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /** Draft → In review (parallel: approve | request changes, which needs a comment) → Approved; changes go back to Draft. */
    private static WorkflowSpec reviewSpec(WorkflowAssignment reviewers, WorkflowReview review) {
        return new WorkflowSpec(List.of(
                state("draft", "Draft", WorkflowStateKind.START, null, null,
                        List.of(transition("submit", "Submit for review", "review", WorkflowTransitionStyle.PRIMARY, false)), List.of()),
                new WorkflowState("review", "In review", WorkflowStateKind.STEP, null, reviewers, 5,
                        List.of(transition("approve", "Approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                                transition("changes", "Request changes", "draft", WorkflowTransitionStyle.NEUTRAL, true)),
                        List.of(), review),
                state("approved", "Approved", WorkflowStateKind.END, null, null, List.of(), List.of())));
    }

    private static WorkflowAssignment chosen() {
        return new WorkflowAssignment(WorkflowAssigneeType.CHOSEN_AT_START, null, null, "Reviewers");
    }

    private WorkflowTaskDTO taskOf(String token, UUID instanceId) {
        return myTasks(token).stream().filter(t -> t.instanceId().equals(instanceId)).findFirst().orElseThrow();
    }

    private boolean hasTask(String token, UUID instanceId) {
        return myTasks(token).stream().anyMatch(t -> t.instanceId().equals(instanceId));
    }

    @Test
    void everyone_reviews_then_one_request_for_changes_sends_it_back_and_a_new_round_approves() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Review all"), reviewSpec(
                users(ADMIN_EMAIL, READER_EMAIL, CONTRIBUTOR_EMAIL), new WorkflowReview(WorkflowReviewRule.ALL, null, "approve"))));
        UUID doc = upload(contributor, null);
        mails().clear();

        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        assertThat(started.currentStateKey()).isEqualTo("review");
        // The initiator is one of the reviewers: the instance shows *their* review.
        assertThat(started.currentTask().candidates()).containsExactly(CONTRIBUTOR_EMAIL);
        WorkflowReviewProgressDTO progress = started.currentTask().review();
        assertThat(progress.rule()).isEqualTo(WorkflowReviewRule.ALL);
        assertThat(progress.total()).isEqualTo(3);
        assertThat(progress.votes()).isEmpty();
        assertThat(progress.pending()).containsExactlyInAnyOrder(ADMIN_EMAIL, READER_EMAIL, CONTRIBUTOR_EMAIL);
        // One task — and one mail — per reviewer, none to the person who just submitted.
        assertThat(mails().ofKind("task")).extracting(CapturingWorkflowMailer.Sent::to).containsExactlyInAnyOrder(ADMIN_EMAIL, READER_EMAIL);

        WorkflowTaskDTO adminTask = taskOf(admin, started.id());
        WorkflowTaskDTO readerTask = taskOf(reader, started.id());
        assertThat(adminTask.id()).isNotEqualTo(readerTask.id());
        assertThat(adminTask.candidates()).containsExactly(ADMIN_EMAIL);
        // Each reviewer acts on their own task only.
        completeRaw(reader, adminTask.id(), "approve", null).expectStatus().isForbidden();

        WorkflowInstanceDTO afterAdmin = complete(admin, adminTask.id(), "approve", "Fine by me");
        assertThat(afterAdmin.currentStateKey()).isEqualTo("review");
        assertThat(afterAdmin.status()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        completeRaw(admin, adminTask.id(), "approve", null).expectStatus().isEqualTo(409);
        assertThat(hasTask(admin, started.id())).isFalse();

        // A request for changes needs a comment, and does not end the round: everyone gets to speak.
        completeRaw(reader, readerTask.id(), "changes", null).expectStatus().isBadRequest();
        WorkflowInstanceDTO afterReader = complete(reader, readerTask.id(), "changes", "Section 2 is outdated");
        assertThat(afterReader.currentStateKey()).isEqualTo("review");
        WorkflowTaskDTO mine = taskOf(contributor, started.id());
        assertThat(mine.review().votes()).extracting(WorkflowReviewProgressDTO.Vote::reviewer).containsExactly(ADMIN_EMAIL, READER_EMAIL);
        assertThat(mine.review().votes()).extracting(WorkflowReviewProgressDTO.Vote::comment).containsExactly("Fine by me", "Section 2 is outdated");
        assertThat(mine.review().approvals()).isEqualTo(1);
        assertThat(mine.review().pending()).containsExactly(CONTRIBUTOR_EMAIL);

        // The last vote closes the round: not unanimous → the rejection that was voted.
        WorkflowInstanceDTO back = complete(contributor, mine.id(), "approve", null);
        assertThat(back.currentStateKey()).isEqualTo("draft");
        WorkflowTaskDTO draft = taskOf(contributor, started.id());
        assertThat(draft.review()).isNull();
        assertThat(draft.previousComment()).contains(ADMIN_EMAIL + ": Fine by me").contains(READER_EMAIL + ": Section 2 is outdated");

        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        List<WorkflowEventDTO> reviewed = detail.history().stream().filter(e -> e.type() == WorkflowEventType.REVIEWED).toList();
        assertThat(reviewed).extracting(WorkflowEventDTO::actor).containsExactly(ADMIN_EMAIL, READER_EMAIL, CONTRIBUTOR_EMAIL);
        assertThat(reviewed).extracting(WorkflowEventDTO::transitionKey).containsExactly("approve", "changes", "approve");
        WorkflowEventDTO decided = detail.history().stream()
                .filter(e -> e.type() == WorkflowEventType.TRANSITIONED && "changes".equals(e.transitionKey())).findFirst().orElseThrow();
        assertThat(decided.fromState()).isEqualTo("review");
        assertThat(decided.toState()).isEqualTo("draft");
        assertThat(decided.details()).containsEntry("review", "ALL").containsEntry("reviewers", 3);

        List<AuditLog> audit = getWebTestClient().get().uri("/api/v1/audit/" + doc)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .exchange().expectStatus().isOk().expectBodyList(AuditLog.class).returnResult().getResponseBody();
        assertThat(audit.stream().filter(a -> a.action() == AuditAction.WORKFLOW_REVIEWED)).hasSize(3);

        // Second round: a fresh set of tasks, earlier votes do not count.
        WorkflowInstanceDTO round2 = complete(contributor, draft.id(), "submit", "Updated section 2");
        assertThat(round2.currentStateKey()).isEqualTo("review");
        assertThat(round2.currentTask().review().votes()).isEmpty();
        assertThat(round2.currentTask().review().total()).isEqualTo(3);
        complete(admin, taskOf(admin, started.id()).id(), "approve", null);
        complete(reader, taskOf(reader, started.id()).id(), "approve", "Good now");
        WorkflowInstanceDTO done = complete(contributor, taskOf(contributor, started.id()).id(), "approve", null);
        assertThat(done.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(done.currentStateKey()).isEqualTo("approved");
    }

    @Test
    void first_rejection_decides_at_once_and_closes_the_other_reviews() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Review first rejection"),
                reviewSpec(chosen(), new WorkflowReview(WorkflowReviewRule.FIRST_REJECTION, null, "approve"))));
        UUID doc = upload(contributor, null);

        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit",
                Map.of("review", List.of(ADMIN_EMAIL, READER_EMAIL)), null);
        assertThat(started.currentTask().review().total()).isEqualTo(2);
        // Not a reviewer: the instance still shows the round, and whom it waits for.
        assertThat(started.currentTask().mine()).isFalse();
        assertThat(started.currentTask().review().pending()).containsExactlyInAnyOrder(ADMIN_EMAIL, READER_EMAIL);

        WorkflowTaskDTO readerTask = taskOf(reader, started.id());
        WorkflowInstanceDTO back = complete(admin, taskOf(admin, started.id()).id(), "changes", "Wrong template");
        assertThat(back.currentStateKey()).isEqualTo("draft");
        assertThat(hasTask(reader, started.id())).isFalse();
        completeRaw(reader, readerTask.id(), "approve", null).expectStatus().isEqualTo(409);
    }

    @Test
    void a_quorum_approves_without_waiting_for_the_rest() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Review quorum"), reviewSpec(
                users(ADMIN_EMAIL, READER_EMAIL, CONTRIBUTOR_EMAIL), new WorkflowReview(WorkflowReviewRule.QUORUM, 2, "approve"))));
        UUID doc = upload(contributor, null);

        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        assertThat(started.currentTask().review().quorum()).isEqualTo(2);
        WorkflowInstanceDTO one = complete(admin, taskOf(admin, started.id()).id(), "approve", null);
        assertThat(one.currentStateKey()).isEqualTo("review");
        WorkflowInstanceDTO two = complete(reader, taskOf(reader, started.id()).id(), "approve", null);
        assertThat(two.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(two.currentStateKey()).isEqualTo("approved");
        assertThat(hasTask(contributor, started.id())).isFalse();
    }

    @Test
    void a_quorum_chosen_at_start_needs_enough_reviewers() {
        String contributor = getAccessToken(CONTRIBUTOR);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Review quorum chosen"),
                reviewSpec(chosen(), new WorkflowReview(WorkflowReviewRule.QUORUM, 2, "approve"))));
        UUID doc = upload(contributor, null);
        startRaw(contributor, new StartWorkflowRequest(def.id(), doc, Map.of("review", List.of(ADMIN_EMAIL)), "submit", null))
                .expectStatus().isBadRequest();
        start(contributor, def.id(), doc, "submit", Map.of("review", List.of(ADMIN_EMAIL, READER_EMAIL)), null);
    }

    @Test
    void a_review_on_a_role_is_refused_at_save() {
        String contributor = getAccessToken(CONTRIBUTOR);
        createDefinitionRaw(contributor, definition(unique("Review role"),
                reviewSpec(role("CONTRIBUTOR"), new WorkflowReview(WorkflowReviewRule.ALL, null, "approve"))))
                .expectStatus().isBadRequest();
    }

    @Test
    void cancelling_closes_every_open_review() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Review cancel"), reviewSpec(
                users(ADMIN_EMAIL, READER_EMAIL), new WorkflowReview(WorkflowReviewRule.ALL, null, "approve"))));
        UUID doc = upload(contributor, null);
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        mails().clear();

        WorkflowInstanceDTO cancelled = authed(getWebTestClient().post().uri(INST + "/" + started.id() + "/cancel"), contributor,
                new CancelInstanceRequest("Superseded"))
                .exchange().expectStatus().isOk().expectBody(WorkflowInstanceDTO.class).returnResult().getResponseBody();
        assertThat(cancelled.status()).isEqualTo(WorkflowInstanceStatus.CANCELLED);
        assertThat(hasTask(admin, started.id())).isFalse();
        assertThat(hasTask(reader, started.id())).isFalse();
        assertThat(mails().ofKind("cancelled")).extracting(CapturingWorkflowMailer.Sent::to).containsExactlyInAnyOrder(ADMIN_EMAIL, READER_EMAIL);
    }
}
