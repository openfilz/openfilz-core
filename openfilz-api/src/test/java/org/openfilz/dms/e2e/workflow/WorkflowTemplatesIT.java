package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowReview;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.enums.WorkflowReviewRule;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.enums.WorkflowTransitionStyle;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Every starter template of the web Designer, end to end: the exact spec the template produces
 * (mirrored from {@code templateSpec()} in openfilz-web {@code src/app/utils/workflow-spec.ts} —
 * keep the two in step), saved through the API, then every branch walked to its final status.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowTemplatesIT extends AbstractWorkflowIT {

    WorkflowTemplatesIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    // ── the templates ─────────────────────────────────────────────────────

    private static WorkflowState s(String key, WorkflowStateKind kind, WorkflowAssignment a, Integer due,
                                   List<WorkflowTransition> t, WorkflowReview review) {
        return new WorkflowState(key, key, kind, null,
                kind == WorkflowStateKind.END ? null : a == null ? WorkflowAssignment.initiator() : a,
                due, t, List.of(), review);
    }

    private static WorkflowState end(String key) {
        return s(key, WorkflowStateKind.END, null, null, List.of(), null);
    }

    private static WorkflowTransition tr(String key, String to, WorkflowTransitionStyle style, boolean comment) {
        return transition(key, key, to, style, comment);
    }

    private static WorkflowAssignment chosen(String label) {
        return new WorkflowAssignment(WorkflowAssigneeType.CHOSEN_AT_START, null, null, label);
    }

    static WorkflowSpec approval() {
        return new WorkflowSpec(List.of(
                s("draft", WorkflowStateKind.START, null, null, List.of(tr("submit", "pending_approval", WorkflowTransitionStyle.PRIMARY, false)), null),
                s("pending_approval", WorkflowStateKind.STEP, chosen("Approver"), 3, List.of(
                        tr("approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                        tr("reject", "rejected", WorkflowTransitionStyle.DANGER, true)), null),
                end("approved"), end("rejected")));
    }

    static WorkflowSpec reviewArchive() {
        return new WorkflowSpec(List.of(
                s("draft", WorkflowStateKind.START, null, null, List.of(tr("submit", "in_review", WorkflowTransitionStyle.PRIMARY, false)), null),
                s("in_review", WorkflowStateKind.STEP, chosen("Reviewer"), 5, List.of(
                        tr("approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                        tr("changes", "draft", WorkflowTransitionStyle.NEUTRAL, true),
                        tr("reject", "rejected", WorkflowTransitionStyle.DANGER, true)), null),
                s("approved", WorkflowStateKind.STEP, WorkflowAssignment.initiator(), null,
                        List.of(tr("archive", "archived", WorkflowTransitionStyle.PRIMARY, false)), null),
                end("archived"), end("rejected")));
    }

    static WorkflowSpec twoStep() {
        return new WorkflowSpec(List.of(
                s("draft", WorkflowStateKind.START, null, null, List.of(tr("submit", "first_approval", WorkflowTransitionStyle.PRIMARY, false)), null),
                s("first_approval", WorkflowStateKind.STEP, chosen("First approver"), 3, List.of(
                        tr("approve", "second_approval", WorkflowTransitionStyle.SUCCESS, false),
                        tr("reject", "rejected", WorkflowTransitionStyle.DANGER, true)), null),
                s("second_approval", WorkflowStateKind.STEP, chosen("Second approver"), 3, List.of(
                        tr("approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                        tr("reject", "rejected", WorkflowTransitionStyle.DANGER, true)), null),
                end("approved"), end("rejected")));
    }

    static WorkflowSpec parallelReview() {
        return new WorkflowSpec(List.of(
                s("draft", WorkflowStateKind.START, null, null, List.of(tr("submit", "in_review", WorkflowTransitionStyle.PRIMARY, false)), null),
                s("in_review", WorkflowStateKind.STEP, chosen("Reviewers"), 5, List.of(
                                tr("approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
                                tr("changes", "draft", WorkflowTransitionStyle.NEUTRAL, true)),
                        new WorkflowReview(WorkflowReviewRule.ALL, null, "approve")),
                end("approved")));
    }

    static WorkflowSpec blank() {
        return new WorkflowSpec(List.of(
                s("draft", WorkflowStateKind.START, null, null, List.of(tr("done", "done", WorkflowTransitionStyle.SUCCESS, false)), null),
                end("done")));
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private UUID define(String contributor, String name, WorkflowSpec spec) {
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique(name), spec));
        assertThat(def.spec().states()).hasSize(spec.states().size());
        return def.id();
    }

    private WorkflowTaskDTO taskOf(String token, UUID instanceId) {
        return myTasks(token).stream().filter(t -> t.instanceId().equals(instanceId)).findFirst().orElseThrow();
    }

    private WorkflowInstanceDTO act(String token, UUID instanceId, String transition, String comment) {
        return complete(token, taskOf(token, instanceId).id(), transition, comment);
    }

    private static void assertEnded(WorkflowInstanceDTO i, String state) {
        assertThat(i.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(i.currentStateKey()).isEqualTo(state);
        assertThat(i.currentTask()).isNull();
    }

    // ── simple approval ───────────────────────────────────────────────────

    @Test
    void approval_template_approves_and_rejects() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        UUID def = define(contributor, "Tpl approval", approval());
        Map<String, List<String>> who = Map.of("pending_approval", List.of(ADMIN_EMAIL));

        WorkflowInstanceDTO a = start(contributor, def, upload(contributor, null), "submit", who, null);
        assertThat(a.currentStateKey()).isEqualTo("pending_approval");
        assertEnded(act(admin, a.id(), "approve", null), "approved");

        WorkflowInstanceDTO r = start(contributor, def, upload(contributor, null), "submit", who, null);
        completeRaw(admin, taskOf(admin, r.id()).id(), "reject", null).expectStatus().isBadRequest();
        assertEnded(act(admin, r.id(), "reject", "No budget"), "rejected");
    }

    // ── review then archive ───────────────────────────────────────────────

    @Test
    void review_archive_template_loops_on_changes_then_archives_or_rejects() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        UUID def = define(contributor, "Tpl review-archive", reviewArchive());
        Map<String, List<String>> who = Map.of("in_review", List.of(ADMIN_EMAIL));

        WorkflowInstanceDTO i = start(contributor, def, upload(contributor, null), "submit", who, null);
        WorkflowInstanceDTO back = act(admin, i.id(), "changes", "Fix the title");
        assertThat(back.currentStateKey()).isEqualTo("draft");
        assertThat(taskOf(contributor, i.id()).previousComment()).isEqualTo("Fix the title");
        assertThat(act(contributor, i.id(), "submit", null).currentStateKey()).isEqualTo("in_review");
        WorkflowInstanceDTO approved = act(admin, i.id(), "approve", null);
        assertThat(approved.currentStateKey()).isEqualTo("approved");
        // "Approved" is a step for the initiator, who archives.
        assertThat(approved.currentTask().candidates()).containsExactly(CONTRIBUTOR_EMAIL);
        assertEnded(act(contributor, i.id(), "archive", null), "archived");

        WorkflowInstanceDTO r = start(contributor, def, upload(contributor, null), "submit", who, null);
        assertEnded(act(admin, r.id(), "reject", "Out of scope"), "rejected");
    }

    // ── two-step approval ─────────────────────────────────────────────────

    @Test
    void two_step_template_needs_both_approvers_and_either_can_reject() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        UUID def = define(contributor, "Tpl two-step", twoStep());
        Map<String, List<String>> who = Map.of("first_approval", List.of(ADMIN_EMAIL), "second_approval", List.of(READER_EMAIL));

        WorkflowInstanceDTO a = start(contributor, def, upload(contributor, null), "submit", who, null);
        WorkflowInstanceDTO second = act(admin, a.id(), "approve", null);
        assertThat(second.currentStateKey()).isEqualTo("second_approval");
        assertThat(second.currentTask().candidates()).containsExactly(READER_EMAIL);
        assertEnded(act(reader, a.id(), "approve", null), "approved");

        WorkflowInstanceDTO r1 = start(contributor, def, upload(contributor, null), "submit", who, null);
        assertEnded(act(admin, r1.id(), "reject", "No"), "rejected");

        WorkflowInstanceDTO r2 = start(contributor, def, upload(contributor, null), "submit", who, null);
        act(admin, r2.id(), "approve", null);
        assertEnded(act(reader, r2.id(), "reject", "Not me"), "rejected");
    }

    // ── parallel review ───────────────────────────────────────────────────

    @Test
    void parallel_review_template_collects_every_reviewer_then_approves_or_sends_back() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        UUID def = define(contributor, "Tpl parallel-review", parallelReview());
        Map<String, List<String>> who = Map.of("in_review", List.of(ADMIN_EMAIL, READER_EMAIL));

        WorkflowInstanceDTO a = start(contributor, def, upload(contributor, null), "submit", who, null);
        assertThat(a.currentTask().review().total()).isEqualTo(2);
        assertThat(act(admin, a.id(), "approve", "OK").currentStateKey()).isEqualTo("in_review");
        assertEnded(act(reader, a.id(), "approve", "OK too"), "approved");

        WorkflowInstanceDTO b = start(contributor, def, upload(contributor, null), "submit", who, null);
        act(reader, b.id(), "changes", "Typos on page 3");
        WorkflowInstanceDTO back = act(admin, b.id(), "approve", null);
        assertThat(back.currentStateKey()).isEqualTo("draft");
        assertThat(taskOf(contributor, b.id()).previousComment()).contains("Typos on page 3");
        assertThat(act(contributor, b.id(), "submit", null).currentTask().review().votes()).isEmpty();
    }

    // ── blank ─────────────────────────────────────────────────────────────

    @Test
    void blank_template_is_a_valid_one_step_workflow() {
        String contributor = getAccessToken(CONTRIBUTOR);
        UUID def = define(contributor, "Tpl blank", blank());
        WorkflowInstanceDTO i = start(contributor, def, upload(contributor, null), null);
        assertThat(i.currentStateKey()).isEqualTo("draft");
        assertEnded(act(contributor, i.id(), "done", null), "done");
    }
}
