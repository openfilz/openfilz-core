package org.openfilz.dms.e2e.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.workflow.CancelInstanceRequest;
import org.openfilz.dms.dto.workflow.MyTasksCountDTO;
import org.openfilz.dms.dto.workflow.ReassignTaskRequest;
import org.openfilz.dms.dto.workflow.StartWorkflowRequest;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowDefinitionDTO;
import org.openfilz.dms.dto.workflow.WorkflowEventDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstanceDetailDTO;
import org.openfilz.dms.dto.workflow.WorkflowInstancePage;
import org.openfilz.dms.dto.workflow.WorkflowSummaryDTO;
import org.openfilz.dms.dto.workflow.WorkflowTaskDTO;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.WorkflowActionType;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowEventType;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.openfilz.dms.enums.WorkflowTaskStatus;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** The engine end to end: start, tasks, transitions, actions, history, reassign, cancel, hot folders, reminders. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class WorkflowInstanceIT extends AbstractWorkflowIT {

    @Autowired
    private WorkflowService workflowService;

    @Autowired
    private DatabaseClient databaseClient;

    WorkflowInstanceIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void approval_happy_path_with_actions_history_audit_and_mails() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        UUID approvedFolder = createFolder(contributor, "Approved");
        List<WorkflowAction> onApproved = List.of(
                new WorkflowAction(WorkflowActionType.MOVE_TO_FOLDER, approvedFolder, null, null),
                new WorkflowAction(WorkflowActionType.SET_METADATA, null, Map.of("status", "approved"), null),
                new WorkflowAction(WorkflowActionType.NOTIFY, null, null, List.of("accounting@test.com")));
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Approval"), approvalSpec(users(ADMIN_EMAIL), onApproved)));
        UUID doc = upload(contributor, null);
        mails().clear();

        // Start & submit in one go: the instance lands in "pending" with a task for the admin.
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        assertThat(started.status()).isEqualTo(WorkflowInstanceStatus.RUNNING);
        assertThat(started.currentStateKey()).isEqualTo("pending");
        assertThat(started.startedBy()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(started.currentTask()).isNotNull();
        assertThat(started.currentTask().candidates()).containsExactly(ADMIN_EMAIL);
        assertThat(started.currentTask().dueAt()).isAfter(OffsetDateTime.now().plusDays(2));
        assertThat(mails().ofKind("task")).extracting(CapturingWorkflowMailer.Sent::to).containsExactly(ADMIN_EMAIL);
        assertThat(mails().ofKind("task").getFirst().link()).isEqualTo("http://web.test/workflows?tab=tasks&task=" + started.currentTask().id());

        // A second workflow on the same document is refused.
        startRaw(contributor, new StartWorkflowRequest(def.id(), doc, null, null, null)).expectStatus().isEqualTo(409);

        // The admin sees it in "My tasks" with the transitions; the contributor does not.
        List<WorkflowTaskDTO> adminTasks = myTasks(admin);
        assertThat(adminTasks).extracting(WorkflowTaskDTO::instanceId).contains(started.id());
        WorkflowTaskDTO task = adminTasks.stream().filter(t -> t.instanceId().equals(started.id())).findFirst().orElseThrow();
        assertThat(task.mine()).isTrue();
        assertThat(task.transitions()).extracting(WorkflowTransition::key).containsExactly("approve", "reject");
        assertThat(task.documentName()).isEqualTo("test.txt");
        assertThat(myTasks(contributor)).extracting(WorkflowTaskDTO::instanceId).doesNotContain(started.id());
        MyTasksCountDTO count = getWebTestClient().get().uri(TASKS + "/mine/count")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .exchange().expectStatus().isOk().expectBody(MyTasksCountDTO.class).returnResult().getResponseBody();
        assertThat(count.count()).isGreaterThanOrEqualTo(1);

        // Reject needs a comment; approve does not.
        completeRaw(admin, task.id(), "reject", null).expectStatus().isBadRequest();
        completeRaw(admin, task.id(), "nope", null).expectStatus().isBadRequest();
        mails().clear();
        WorkflowInstanceDTO done = complete(admin, task.id(), "approve", "Looks good");
        assertThat(done.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(done.currentStateKey()).isEqualTo("approved");
        assertThat(done.completedAt()).isNotNull();
        assertThat(done.currentTask()).isNull();
        completeRaw(admin, task.id(), "approve", null).expectStatus().isEqualTo(409);

        // Actions ran: moved + metadata stamped.
        DocumentInfo info = getWebTestClient().get().uri(u -> u.path("/api/v1/documents/" + doc + "/info").queryParam("withMetadata", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(DocumentInfo.class).returnResult().getResponseBody();
        assertThat(info.parentId()).isEqualTo(approvedFolder);
        assertThat(info.metadata()).containsEntry("status", "approved");

        // Completion notices: initiator + NOTIFY target.
        assertThat(mails().ofKind("completed")).extracting(CapturingWorkflowMailer.Sent::to)
                .containsExactlyInAnyOrder(CONTRIBUTOR_EMAIL, "accounting@test.com");

        // History.
        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        assertThat(detail.spec().states()).hasSize(4);
        assertThat(detail.canManage()).isTrue();
        assertThat(detail.history()).extracting(WorkflowEventDTO::type).containsSubsequence(
                WorkflowEventType.STARTED, WorkflowEventType.TRANSITIONED, WorkflowEventType.TRANSITIONED, WorkflowEventType.COMPLETED);
        assertThat(detail.history()).extracting(WorkflowEventDTO::type).contains(WorkflowEventType.ACTION_APPLIED);
        assertThat(detail.history()).extracting(WorkflowEventDTO::type).doesNotContain(WorkflowEventType.ACTION_FAILED);
        WorkflowEventDTO approve = detail.history().stream().filter(e -> "approve".equals(e.transitionKey())).findFirst().orElseThrow();
        assertThat(approve.actor()).isEqualTo(ADMIN_EMAIL);
        assertThat(approve.comment()).isEqualTo("Looks good");

        // Audit trail on the document names the real actors.
        List<AuditLog> audit = getWebTestClient().get().uri("/api/v1/audit/" + doc)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                .exchange().expectStatus().isOk().expectBodyList(AuditLog.class).returnResult().getResponseBody();
        assertThat(audit).extracting(AuditLog::action).contains(AuditAction.WORKFLOW_STARTED, AuditAction.WORKFLOW_TRANSITIONED,
                AuditAction.WORKFLOW_COMPLETED, AuditAction.MOVE_FILE, AuditAction.UPDATE_DOCUMENT_METADATA);
        assertThat(audit.stream().filter(a -> a.action() == AuditAction.WORKFLOW_COMPLETED).findFirst().orElseThrow().username()).isEqualTo(ADMIN_EMAIL);
        assertThat(audit.stream().filter(a -> a.action() == AuditAction.MOVE_FILE).findFirst().orElseThrow().username()).isEqualTo(ADMIN_EMAIL);

        // Monitor listing + summary.
        WorkflowInstancePage page = getWebTestClient().get().uri(u -> u.path(INST).queryParam("documentId", doc).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken(READER))
                .exchange().expectStatus().isOk().expectBody(WorkflowInstancePage.class).returnResult().getResponseBody();
        assertThat(page.items()).extracting(WorkflowInstanceDTO::id).containsExactly(started.id());
        assertThat(page.total()).isEqualTo(1);
        WorkflowSummaryDTO summary = getWebTestClient().get().uri(INST + "/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(WorkflowSummaryDTO.class).returnResult().getResponseBody();
        assertThat(summary.completed()).isGreaterThanOrEqualTo(1);
        assertThat(summary.byDefinition()).extracting(WorkflowSummaryDTO.PerDefinition::definitionId).contains(def.id());

        // A new instance may now start on the same document (the previous one is over).
        start(contributor, def.id(), doc, null);
    }

    @Test
    void rejection_comment_reaches_the_timeline_and_the_initiator_task() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Reject"), approvalSpec(users(ADMIN_EMAIL), List.of())));
        UUID doc = upload(contributor, null);
        // Start without submitting: the initiator holds the draft task first.
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, null);
        assertThat(started.currentStateKey()).isEqualTo("draft");
        WorkflowTaskDTO draft = myTasks(contributor).stream().filter(t -> t.instanceId().equals(started.id())).findFirst().orElseThrow();
        assertThat(draft.candidates()).containsExactly(CONTRIBUTOR_EMAIL);
        completeRaw(admin, draft.id(), "submit", null).expectStatus().isForbidden();
        complete(contributor, draft.id(), "submit", "Please review");
        WorkflowTaskDTO pending = myTasks(admin).stream().filter(t -> t.instanceId().equals(started.id())).findFirst().orElseThrow();
        assertThat(pending.previousComment()).isEqualTo("Please review");
        assertThat(pending.previousActor()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(mails().to(ADMIN_EMAIL).stream().filter(s -> s.taskId() != null && s.taskId().equals(pending.id())).findFirst().orElseThrow().comment())
                .isEqualTo("Please review");
        WorkflowInstanceDTO rejected = complete(admin, pending.id(), "reject", "Budget missing");
        assertThat(rejected.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        assertThat(rejected.currentStateKey()).isEqualTo("rejected");
        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        assertThat(detail.history().stream().filter(e -> "reject".equals(e.transitionKey())).findFirst().orElseThrow().comment()).isEqualTo("Budget missing");
    }

    @Test
    void role_candidates_chosen_at_start_reassign_and_cancel() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        String reader = getAccessToken(READER);
        // Approval by anyone holding AUDITOR (admin-user does) — then "chosen at start" archiving.
        WorkflowAssignment chosen = new WorkflowAssignment(WorkflowAssigneeType.CHOSEN_AT_START, null, null, "Archivist");
        var spec = new org.openfilz.dms.dto.workflow.WorkflowSpec(List.of(
                state("draft", "Draft", org.openfilz.dms.enums.WorkflowStateKind.START, null, null,
                        List.of(transition("submit", "Submit", "audit", org.openfilz.dms.enums.WorkflowTransitionStyle.PRIMARY, false)), List.of()),
                state("audit", "Audit", org.openfilz.dms.enums.WorkflowStateKind.STEP, role("AUDITOR"), null,
                        List.of(transition("ok", "OK", "archive", org.openfilz.dms.enums.WorkflowTransitionStyle.SUCCESS, false)), List.of()),
                state("archive", "To archive", org.openfilz.dms.enums.WorkflowStateKind.STEP, chosen, null,
                        List.of(transition("done", "Archived", "end", org.openfilz.dms.enums.WorkflowTransitionStyle.SUCCESS, false)), List.of()),
                state("end", "Archived", org.openfilz.dms.enums.WorkflowStateKind.END, null, null, List.of(), List.of())));
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Roles"), spec));
        UUID doc = upload(contributor, null);

        // The starter must name the archivist.
        startRaw(contributor, new StartWorkflowRequest(def.id(), doc, null, "submit", null)).expectStatus().isBadRequest();
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit", Map.of("archive", List.of("Reader-User@test.com")), null);
        assertThat(started.currentStateKey()).isEqualTo("audit");
        assertThat(started.currentTask().candidateRole()).isEqualTo("AUDITOR");
        assertThat(started.currentTask().candidates()).isEmpty();

        // Role task: visible to the admin (AUDITOR), not to the reader; the reader is refused.
        WorkflowTaskDTO auditTask = myTasks(admin).stream().filter(t -> t.instanceId().equals(started.id())).findFirst().orElseThrow();
        assertThat(myTasks(reader)).extracting(WorkflowTaskDTO::instanceId).doesNotContain(started.id());
        completeRaw(reader, auditTask.id(), "ok", null).expectStatus().isForbidden();

        // The initiator may hand the role task to a named person.
        authed(getWebTestClient().post().uri(TASKS + "/" + auditTask.id() + "/reassign"), reader, new ReassignTaskRequest(List.of(READER_EMAIL), null))
                .exchange().expectStatus().isForbidden();
        WorkflowInstanceDTO reassigned = authed(getWebTestClient().post().uri(TASKS + "/" + auditTask.id() + "/reassign"), contributor,
                new ReassignTaskRequest(List.of(READER_EMAIL), "You take it"))
                .exchange().expectStatus().isOk().expectBody(WorkflowInstanceDTO.class).returnResult().getResponseBody();
        assertThat(reassigned.currentTask().candidates()).containsExactly(READER_EMAIL);
        assertThat(reassigned.currentTask().candidateRole()).isNull();
        assertThat(myTasks(admin)).extracting(WorkflowTaskDTO::instanceId).doesNotContain(started.id());

        // A READER can complete a task assigned to them.
        WorkflowInstanceDTO archiving = complete(reader, auditTask.id(), "ok", null);
        assertThat(archiving.currentStateKey()).isEqualTo("archive");
        assertThat(archiving.currentTask().candidates()).containsExactly(READER_EMAIL);

        // Cancel: only the initiator; the open task is closed, everyone but the actor is told.
        authed(getWebTestClient().post().uri(INST + "/" + started.id() + "/cancel"), admin, new CancelInstanceRequest("no"))
                .exchange().expectStatus().isForbidden();
        mails().clear();
        WorkflowInstanceDTO cancelled = authed(getWebTestClient().post().uri(INST + "/" + started.id() + "/cancel"), contributor,
                new CancelInstanceRequest("Not needed anymore"))
                .exchange().expectStatus().isOk().expectBody(WorkflowInstanceDTO.class).returnResult().getResponseBody();
        assertThat(cancelled.status()).isEqualTo(WorkflowInstanceStatus.CANCELLED);
        assertThat(cancelled.currentTask()).isNull();
        assertThat(mails().ofKind("cancelled")).extracting(CapturingWorkflowMailer.Sent::to).containsExactly(READER_EMAIL);
        assertThat(myTasks(reader)).extracting(WorkflowTaskDTO::instanceId).doesNotContain(started.id());
        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        assertThat(detail.history()).extracting(WorkflowEventDTO::type).contains(WorkflowEventType.REASSIGNED, WorkflowEventType.CANCELLED);
        completeRaw(reader, archiving.currentTask().id(), "done", null).expectStatus().isEqualTo(409);
    }

    @Test
    void hot_folder_starts_the_workflow_and_takes_the_first_transition() {
        String contributor = getAccessToken(CONTRIBUTOR);
        UUID inbox = createFolder(contributor, "Inbox");
        WorkflowDefinitionDTO def = createDefinition(contributor,
                definition(unique("Hot"), approvalSpec(users(ADMIN_EMAIL), List.of()), List.of(inbox)));
        UUID elsewhere = upload(contributor, null);
        UUID inInbox = upload(contributor, inbox);

        WorkflowInstancePage none = getWebTestClient().get().uri(u -> u.path(INST).queryParam("documentId", elsewhere).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(WorkflowInstancePage.class).returnResult().getResponseBody();
        assertThat(none.items()).isEmpty();

        WorkflowInstancePage page = getWebTestClient().get().uri(u -> u.path(INST).queryParam("documentId", inInbox).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(WorkflowInstancePage.class).returnResult().getResponseBody();
        assertThat(page.items()).hasSize(1);
        WorkflowInstanceDTO auto = page.items().getFirst();
        assertThat(auto.definitionId()).isEqualTo(def.id());
        assertThat(auto.startedBy()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(auto.currentStateKey()).isEqualTo("pending");
        assertThat(auto.currentTask().candidates()).containsExactly(ADMIN_EMAIL);
    }

    @Test
    void overdue_tasks_are_reminded_once() {
        String contributor = getAccessToken(CONTRIBUTOR);
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Overdue"), approvalSpec(users(ADMIN_EMAIL), List.of())));
        UUID doc = upload(contributor, null);
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        UUID taskId = started.currentTask().id();
        // No API moves time: back-date the due date directly (the one DB seam of this suite).
        databaseClient.sql("UPDATE workflow_task SET due_at = :d WHERE id = :id")
                .bind("d", OffsetDateTime.now().minusDays(1)).bind("id", taskId).fetch().rowsUpdated().block();
        mails().clear();

        Long reminded = workflowService.remindOverdue().block();
        assertThat(reminded).isGreaterThanOrEqualTo(1);
        assertThat(mails().ofKind("overdue").stream().filter(s -> taskId.equals(s.taskId()))).hasSize(1);
        assertThat(workflowService.remindOverdue().block()).isZero();

        WorkflowTaskDTO task = myTasks(getAccessToken(ADMIN)).stream().filter(t -> t.id().equals(taskId)).findFirst().orElseThrow();
        assertThat(task.overdue()).isTrue();
        assertThat(task.status()).isEqualTo(WorkflowTaskStatus.OPEN);
        WorkflowSummaryDTO summary = getWebTestClient().get().uri(INST + "/summary")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk().expectBody(WorkflowSummaryDTO.class).returnResult().getResponseBody();
        assertThat(summary.overdue()).isGreaterThanOrEqualTo(1);
        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        assertThat(detail.history()).extracting(WorkflowEventDTO::type).contains(WorkflowEventType.REMINDED);
    }

    @Test
    void a_failed_action_is_recorded_but_never_blocks_the_transition() {
        String contributor = getAccessToken(CONTRIBUTOR);
        String admin = getAccessToken(ADMIN);
        List<WorkflowAction> broken = List.of(new WorkflowAction(WorkflowActionType.MOVE_TO_FOLDER, UUID.randomUUID(), null, null));
        WorkflowDefinitionDTO def = createDefinition(contributor, definition(unique("Broken action"), approvalSpec(users(ADMIN_EMAIL), broken)));
        UUID doc = upload(contributor, null);
        WorkflowInstanceDTO started = start(contributor, def.id(), doc, "submit");
        WorkflowInstanceDTO done = complete(admin, started.currentTask().id(), "approve", null);
        assertThat(done.status()).isEqualTo(WorkflowInstanceStatus.COMPLETED);
        WorkflowInstanceDetailDTO detail = getInstance(contributor, started.id());
        WorkflowEventDTO failed = detail.history().stream().filter(e -> e.type() == WorkflowEventType.ACTION_FAILED).findFirst().orElseThrow();
        assertThat(failed.details()).containsEntry("action", "MOVE_TO_FOLDER");
        assertThat(failed.details()).containsKey("error");
    }
}
