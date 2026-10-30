# Workflows — internals

Contributor notes for the workflows feature: the design decisions and mechanics behind
[the public page](../workflows.md), which describes what the feature does and how to configure it.
This page is not published on the website.

## Life of an instance (engine view)

1. **Start** — `POST /workflows/instances {definitionId, documentId, assignments?, transitionKey?, comment?}`.
   The document must be an active file the caller may read (`WorkflowAccessPolicy.canStart`).
   The spec is snapshotted, the instance enters the `START` state (a task for the initiator),
   and if `transitionKey` names one of the `START` state's transitions it is applied at once —
   the start dialog shows those transitions as buttons (*Start & submit for approval*).
2. **Enter a state** — a task is created for the state's candidates (resolved from the
   assignment: initiator, listed e-mails, role, or the starter's choice), `dueAt` is computed,
   `onEnter` actions run, every candidate is notified (`WorkflowNotifier` + `WorkflowMailer`).
   For an `END` state no task is created: the instance becomes `COMPLETED`, the initiator is
   notified.
3. **Act** — `POST /workflows/tasks/{id}/complete {transitionKey, comment?}`. The caller must be
   a candidate (e-mail in the candidate list, or holding the candidate role), the task must be
   `OPEN`, the transition must belong to the current state; a `requireComment` transition
   without a comment is `400`. The task is closed, a `TRANSITIONED` history entry (with the
   comment) is written, the `WorkflowCommentBridge` seam is told, and step 2 repeats for the
   target state. Everything up to the target state's task is one transaction; notifications
   and actions run after it. On a parallel review the task is one reviewer's vote: it is closed,
   a `REVIEWED` entry (+ `WORKFLOW_REVIEWED` audit) records it, and the document moves only once
   the rule decides. Completions lock the instance row, so two reviewers voting at the same
   moment are serialised and the last vote always sees all the others.
4. **Reassign** — `POST /workflows/tasks/{id}/reassign {emails}` by the initiator or a current
   candidate (audit + `REASSIGNED`).
5. **Cancel** — `POST /workflows/instances/{id}/cancel` by the initiator (or anyone
   `WorkflowAccessPolicy.canManage` allows). Open tasks (all of a review round) closed as `CANCELLED`.
6. **Sweeper** — `openfilz.workflows.sweep.cron` (hourly): open tasks past `dueAt` and not yet
   reminded get one reminder (`REMINDED`, `remindedAt`).

Auto-start: after every successful upload (`/documents/upload`, `/upload-multiple`, TUS
finalize) `WorkflowTriggerService.afterUpload` starts the active definitions whose
`triggerFolderIds` contain the file's parent — as the uploader. Smart filing (if on) runs
first, so the trigger sees the final folder.

---

## Data model (`V1_11__create_workflow_schema.sql`)

| Table | Columns (abridged) |
|---|---|
| `workflow_definition` | `id`, `name` (unique, case-insensitive), `description`, `active`, `spec JSONB`, `trigger_folder_ids JSONB`, `version`, `created_by`, `created_at`, `updated_at` |
| `workflow_instance` | `id`, `definition_id`, `definition_name`, `definition_version`, `spec JSONB` (snapshot), `document_id`, `document_name`, `status` (`RUNNING\|COMPLETED\|CANCELLED`), `current_state_key`, `current_state_label`, `started_by`, `assignments JSONB`, `locale`, `started_at`, `updated_at`, `completed_at`. Partial unique index on `document_id WHERE status = 'RUNNING'`. |
| `workflow_task` | `id`, `instance_id`, `state_key`, `state_label`, `candidate_role`, `status` (`OPEN\|DONE\|CANCELLED`), `due_at`, `reminded_at`, `created_at`, `completed_at`, `completed_by`, `transition_key`, `comment`, `review_group` (`V1_14`: shared by the tasks of one parallel review round, null otherwise) |
| `workflow_task_candidate` | `task_id`, `email` (lower-cased) — one row per candidate, indexed on `email` for *My tasks* |
| `workflow_event` | `id`, `instance_id`, `event_type`, `from_state`, `to_state`, `transition_key`, `actor`, `comment`, `details JSONB`, `created_at` |

Nothing references `documents` with a foreign key on purpose: an instance must survive the
document being moved to the recycle bin (the monitor shows it as such), and the schema stays
idempotent.

## Security model

* All endpoints sit behind the OIDC chain; `AbstractSecurityService.isWorkflowAuthorized` is
  the role hook (an extension may override it). Path constant `RestApiVersion.ENDPOINT_WORKFLOWS`.
* **Document access** is delegated to `WorkflowAccessPolicy`: `canStart(document, email)`
  (core: the document is an active file), `canView(instance, email)` (core: everyone),
  `canManage(instance, email)` (core: the initiator).
* **Definition access** too: `visibleDefinitions(email, roles)` is the test the catalogue, the
  start dialog and every definition id handed to the API go through (core: every definition is
  visible; an invisible one answers `404`), `canEditDefinition(definition, email, roles)` decides
  who may change a visible one (core: everyone). A hot folder is not asked — an upload into it
  starts its workflow whoever uploads, as the designer set it up.
* **Task completion** is bound to the candidate list, never to the document's permissions: an
  approver may be someone who cannot otherwise see the file. Actions (`MOVE_TO_FOLDER`,
  `SET_METADATA`) run through the normal `DocumentService` under the actor's `Authentication`,
  so the audit log names the real person and any ownership checks an extension adds still apply —
  which is exactly why an action can fail and is recorded rather than swallowed.
* Assignee e-mails are stored lower-cased and compared case-insensitively to the `email` claim.
* Audit: `WORKFLOW_STARTED`, `WORKFLOW_TRANSITIONED`, `WORKFLOW_COMPLETED`, `WORKFLOW_CANCELLED`,
  `WORKFLOW_TASK_REASSIGNED`, `WORKFLOW_DEFINITION_CREATED / _UPDATED / _DELETED`, always on the
  document (or the definition id for definition changes).

## Web app implementation

All UI strings live under `workflow.*` in the 8 locales. Everything is in dedicated files
(`models/workflow.models.ts`, `services/workflow*.ts`, `pages/workflows/**`,
`dialogs/start-workflow-dialog`, `components/workflow-diagram`, `components/metadata-panel/document-workflow`)
so a fork only has to mirror the descriptor entries and the route.

## Extension points

| Seam (`service/workflow/`) | Core default |
|---|---|
| `WorkflowNotifier` | `NoopWorkflowNotifier` |
| `WorkflowMailer` | `SmtpWorkflowMailer` / `LoggingWorkflowMailer` (`workflow-mail/messages_*.properties`) |
| `WorkflowAccessPolicy` | active file / everyone / initiator; every definition visible and editable |
| `WorkflowCommentBridge` | no-op |
| `WorkflowActorResolver` | synthetic `JwtAuthenticationToken` from the stored e-mail (sweeper, auto-start) |
| `AbstractSecurityService.isWorkflowAuthorized` | the roles of the public page |

Register your own bean `@Primary` to take over a seam.

Follow-ups not in this version: AI/MCP tools (`startWorkflow`, `listMyTasks`, `completeTask`),
conditions on metadata, and per-folder default workflows in the upload dialog.

## Settings and tests

`GET /api/v1/settings` carries `workflowsActive` and `workflowDesignerRoleRequired`.

Tests: `e2e/workflow/WorkflowDefinitionIT` (CRUD + validation), `WorkflowInstanceIT`
(start / act / actions / history / cancel / reassign / role candidates / auto-start),
`WorkflowSecurityIT` (roles, non-candidate `403`, designer role), `WorkflowDisabledIT`
(everything `404`, settings flag off), `WorkflowSpecValidatorTest` (unit). The ITs capture
mails through a `@Primary` `CapturingWorkflowMailer`, like e-Sign.
