# Workflows — statuses, transitions and tasks (Community Edition)

OpenFilz can run the classic document workflows of a DMS — *Draft → Pending approval →
Approved | Rejected → Archived* — natively, with no external engine (n8n, Camunda, …). A
workflow is a small state machine attached to one document: every **status** names who has
to act, every **transition** is a button those people see, and reaching a status can trigger
**actions** on the document (move it to a folder, stamp metadata, notify people).

Everything on this page is part of the open-source core (`openfilz-api`, AGPL-3.0). The
Enterprise Edition plugs into the seams listed in [§9](#9-extension-points-and-what-the-enterprise-edition-adds)
(in-app notifications, comments, user picker, document-permission scoping) — the engine, the
designer, the monitor and *My tasks* are the same in both editions.

---

## 1. Concepts

| Concept | Behaviour |
|---|---|
| **Workflow definition** | A named, versioned list of **statuses** (`START`, `STEP`, `END`), each with its assignees, its outgoing transitions, an optional due delay and optional *on-enter* actions. Stored as one JSON document (`spec`, see [§3](#3-the-definition-spec-json)); edited in the *Designer*. Deactivating a definition hides it from the *Start workflow* dialog without touching running instances. |
| **Instance** | One document going through one definition. `RUNNING → COMPLETED \| CANCELLED`. The definition's spec is **snapshotted** at start, so editing a definition never breaks what is already running. A document has **at most one running instance** (`409` otherwise). |
| **Task** | "The document *X* is in status *S* and waits for one of *these people*". Created every time an instance enters a status that has assignees; closed when one candidate picks a transition. Candidates are e-mail addresses and/or a realm role (any user holding the role sees the task). Any candidate may act — first come, first served. |
| **Transition** | A labelled button (`Approve`, `Reject`, `Send back`, …) that moves the instance to a target status. A transition may **require a comment** (typical for *Reject*). |
| **Decision comment** | The optional/required note the actor leaves when taking a transition. Stored on the history entry, shown in the timeline and to the next assignee. |
| **Actions (on enter)** | What OpenFilz does when the document reaches a status: `MOVE_TO_FOLDER`, `SET_METADATA`, `NOTIFY`. They run as the actor who took the transition (audit attribution) and **never block** the transition: a failed action is recorded in the history (`ACTION_FAILED`) and surfaced in the monitor. |
| **Trigger folders** | Optional: a definition may name folders in which every new upload starts the workflow automatically (hot folder). Definitions with a `CHOSEN_AT_START` assignee cannot be triggered that way (nobody is there to choose). |
| **Due date & reminders** | A status may carry `dueInDays`. The task gets a `dueAt`; the hourly sweeper mails an overdue reminder once, and the monitor / *My tasks* flag overdue tasks. |
| **History** | Append-only trail per instance (`STARTED`, `TRANSITIONED`, `ACTION_APPLIED`, `ACTION_FAILED`, `REASSIGNED`, `REMINDED`, `COMPLETED`, `CANCELLED`), plus `WORKFLOW_*` entries in the tamper-evident audit log. |

---

## 2. Quick start

```yaml
openfilz:
  workflows:
    active: true                                    # OPENFILZ_WORKFLOWS_ACTIVE
  common:
    web-public-base-url: https://app.example.com/   # OPENFILZ_WEB_PUBLIC_BASE_URL (links in e-mails)
spring:
  mail:
    host: smtp.example.com                          # SMTP_HOST … (same block as e-Sign)
```

* `openfilz.workflows.active` is the master switch, default `false`. It is read at runtime
  (never a bean condition) so a single native image serves both kinds of deployment. When off,
  `/api/v1/workflows/**` answers `404`, the sweeper idles and `GET /api/v1/settings` reports
  `workflowsActive: false` so the web app hides the *Workflows* menu.
* Without SMTP the mailer only logs (`[workflows][no-smtp] …`); the in-app *My tasks* page is
  then the only invitation channel in CE (EE adds the notification bell).

Then, in the web app: **Workflows → Designer → New workflow**, pick the *Simple approval*
template, name the approver(s), save. Open any document → **Start workflow** → *Submit for
approval*. The approver finds it under **Workflows → My tasks** (and in their inbox).

---

## 3. The definition spec (JSON)

```jsonc
{
  "states": [
    {
      "key": "draft", "label": "Draft", "kind": "START", "color": "#94a3b8",
      "assignees": { "type": "INITIATOR" },
      "transitions": [
        { "key": "submit", "label": "Submit for approval", "to": "pending_approval", "style": "primary" }
      ]
    },
    {
      "key": "pending_approval", "label": "Pending approval", "kind": "STEP", "color": "#f59e0b",
      "assignees": { "type": "USERS", "emails": ["alice@example.com", "bob@example.com"] },
      "dueInDays": 3,
      "transitions": [
        { "key": "approve", "label": "Approve", "to": "approved", "style": "success" },
        { "key": "reject",  "label": "Reject",  "to": "rejected", "style": "danger", "requireComment": true }
      ]
    },
    {
      "key": "approved", "label": "Approved", "kind": "END", "color": "#10b981",
      "onEnter": [
        { "type": "MOVE_TO_FOLDER", "folderId": "8c1e…" },
        { "type": "SET_METADATA", "entries": { "status": "approved" } },
        { "type": "NOTIFY", "emails": ["accounting@example.com"] }
      ]
    },
    { "key": "rejected", "label": "Rejected", "kind": "END", "color": "#ef4444" }
  ]
}
```

| Field | Rules (enforced by `WorkflowSpecValidator`, mirrored client-side) |
|---|---|
| `states[].key` | `^[a-z0-9_]{1,40}$`, unique. |
| `kind` | Exactly one `START`; at least one `END`. `END` states have no assignees, no transitions and no due delay. Every state must be reachable from `START`, and every non-`END` state must reach an `END`. |
| `assignees.type` | `INITIATOR` (the person who started the instance), `USERS` (`emails`, ≥ 1, lower-cased), `ROLE` (`role` = a realm role name, e.g. `CONTRIBUTOR`), `CHOSEN_AT_START` (`label` shown in the start dialog; the starter names the people). Absent on `START` = `INITIATOR`. |
| `transitions[]` | `key` unique inside the state, `label` ≤ 60 chars, `to` must exist, `style` ∈ `primary \| success \| danger \| neutral`, `requireComment` default `false`. |
| `onEnter[]` | `MOVE_TO_FOLDER {folderId}`, `SET_METADATA {entries: {k: v}}` (≤ 20 keys, keys must not start with `_`), `NOTIFY {emails}`. |
| `dueInDays` | 1..365. |

Definitions also carry `name` (unique, ≤ 100), `description`, `active`, `triggerFolderIds` and
the bookkeeping columns. The web *Designer* offers templates (*Simple approval*, *Review then
archive*, *Two-step approval*) that produce this JSON — nobody has to write it by hand.

---

## 4. Life of an instance

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
   and actions run after it.
4. **Reassign** — `POST /workflows/tasks/{id}/reassign {emails}` by the initiator or a current
   candidate (audit + `REASSIGNED`).
5. **Cancel** — `POST /workflows/instances/{id}/cancel` by the initiator (or anyone
   `WorkflowAccessPolicy.canManage` allows). Open task closed as `CANCELLED`.
6. **Sweeper** — `openfilz.workflows.sweep.cron` (hourly): open tasks past `dueAt` and not yet
   reminded get one reminder (`REMINDED`, `remindedAt`).

Auto-start: after every successful upload (`/documents/upload`, `/upload-multiple`, TUS
finalize) `WorkflowTriggerService.afterUpload` starts the active definitions whose
`triggerFolderIds` contain the file's parent — as the uploader. Smart filing (if on) runs
first, so the trigger sees the final folder.

---

## 5. Data model (`V1_11__create_workflow_schema.sql`)

| Table | Columns (abridged) |
|---|---|
| `workflow_definition` | `id`, `name` (unique, case-insensitive), `description`, `active`, `spec JSONB`, `trigger_folder_ids JSONB`, `version`, `created_by`, `created_at`, `updated_at` |
| `workflow_instance` | `id`, `definition_id`, `definition_name`, `definition_version`, `spec JSONB` (snapshot), `document_id`, `document_name`, `status` (`RUNNING\|COMPLETED\|CANCELLED`), `current_state_key`, `current_state_label`, `started_by`, `assignments JSONB`, `locale`, `started_at`, `updated_at`, `completed_at`. Partial unique index on `document_id WHERE status = 'RUNNING'`. |
| `workflow_task` | `id`, `instance_id`, `state_key`, `state_label`, `candidate_role`, `status` (`OPEN\|DONE\|CANCELLED`), `due_at`, `reminded_at`, `created_at`, `completed_at`, `completed_by`, `transition_key`, `comment` |
| `workflow_task_candidate` | `task_id`, `email` (lower-cased) — one row per candidate, indexed on `email` for *My tasks* |
| `workflow_event` | `id`, `instance_id`, `event_type`, `from_state`, `to_state`, `transition_key`, `actor`, `comment`, `details JSONB`, `created_at` |

Nothing references `documents` with a foreign key on purpose: an instance must survive the
document being moved to the recycle bin (the monitor shows it as such), and the schema stays
idempotent for enterprise databases.

---

## 6. REST API — `/api/v1/workflows` (OIDC)

Roles: every `GET` needs `READER` or `CONTRIBUTOR`; definition writes need `CONTRIBUTOR`
(plus `WORKFLOW_DESIGNER` when `openfilz.workflows.require-designer-role=true`); starting,
cancelling and reassigning need `CONTRIBUTOR`; **completing a task only needs to be a
candidate** (`READER` is enough — an approver does not have to be a contributor).

### Definitions
| Method | Path | Notes |
|---|---|---|
| `GET` | `/definitions?active=` | All definitions, with `runningCount`. |
| `POST` | `/definitions` | `{name, description?, active?, spec, triggerFolderIds?}` → `201`; `400` with the list of problems when invalid; `409` on a duplicate name. |
| `GET` | `/definitions/{id}` | |
| `PUT` | `/definitions/{id}` | Same body; bumps `version`. Running instances keep their snapshot. |
| `DELETE` | `/definitions/{id}` | `409` while instances are running. |
| `POST` | `/definitions/validate` | Dry run → `{problems: [{path, code, message}]}` (the designer calls it on save). |

### Instances
| Method | Path | Notes |
|---|---|---|
| `POST` | `/instances` | Start (see §4). `409` when the document already has a running instance. |
| `GET` | `/instances?documentId=&definitionId=&status=&state=&mine=&page=&size=` | Newest first; `mine=true` = started by me. |
| `GET` | `/instances/summary` | `{running, completed, cancelled, overdue, byDefinition: [...]}` for the monitor header. |
| `GET` | `/instances/{id}` | Instance + open task (with candidates) + full history + the snapshot spec (the diagram is drawn from it). |
| `POST` | `/instances/{id}/cancel` | `{comment?}` |

### Tasks
| Method | Path | Notes |
|---|---|---|
| `GET` | `/tasks/mine` | Open tasks I can act on (e-mail or role), oldest first, with the document, the instance, the available transitions and the previous decision comment. |
| `GET` | `/tasks/mine/count` | `{count, overdue}` — the sidebar badge. |
| `POST` | `/tasks/{id}/complete` | `{transitionKey, comment?}` → the updated instance. `403` not a candidate, `409` already done, `400` comment required. |
| `POST` | `/tasks/{id}/reassign` | `{emails}` |

`GET /api/v1/settings` gains `workflowsActive` and `workflowDesignerRoleRequired`.

---

## 7. Security model

* All endpoints sit behind the OIDC chain; `AbstractSecurityService.isWorkflowAuthorized` is
  the role hook (editions override it). Path constant `RestApiVersion.ENDPOINT_WORKFLOWS`.
* **Document access** is delegated to `WorkflowAccessPolicy`: `canStart(document, email)`
  (core: the document is an active file), `canView(instance, email)` (core: everyone),
  `canManage(instance, email)` (core: the initiator). The EE policy answers from its
  ownership/share model.
* **Task completion** is bound to the candidate list, never to the document's permissions: an
  approver may be someone who cannot otherwise see the file. Actions (`MOVE_TO_FOLDER`,
  `SET_METADATA`) run through the normal `DocumentService` under the actor's `Authentication`,
  so the audit log names the real person and edition-specific ownership checks still apply —
  which is exactly why an action can fail and is recorded rather than swallowed.
* Assignee e-mails are stored lower-cased and compared case-insensitively to the `email` claim.
* Audit: `WORKFLOW_STARTED`, `WORKFLOW_TRANSITIONED`, `WORKFLOW_COMPLETED`, `WORKFLOW_CANCELLED`,
  `WORKFLOW_TASK_REASSIGNED`, `WORKFLOW_DEFINITION_CREATED / _UPDATED / _DELETED`, always on the
  document (or the definition id for definition changes).

---

## 8. Web app

* **Sidebar → Workflows** (badge = my open tasks, red when any is overdue). Page `/workflows`
  with three tabs, deep-linkable with `?tab=` / `?task=` / `?instance=`:
  * **My tasks** — one card per task: document (opens the file), workflow + status chip,
    waiting since / due, the previous actor's comment, and the transition buttons right on the
    card. A transition with `requireComment` (or the *Add a comment* link) opens a small
    dialog. Done tasks disappear; the badge updates.
  * **Monitor** — filters (definition, status, current state, mine), a table of instances,
    and a side drawer with the **diagram** (statuses laid out left-to-right, the current one
    highlighted, taken transitions in bold), the open task's candidates, the timeline, and
    *Reassign* / *Cancel*.
  * **Designer** — definition cards (name, statuses, running count, active switch) and the
    editor: name / description / trigger folders on the left, one card per status (label,
    colour, kind, assignees, due delay, transitions as chips → target picker, on-enter
    actions), and the live diagram on the right. Validation problems are shown inline before
    save (client-side mirror of `WorkflowSpecValidator`, then `/definitions/validate`).
* **Start workflow** — item action on files (menu, toolbar, details panel): choose a
  definition, name the *chosen at start* people, optional comment, then either *Start* or
  one of the first transitions (*Start & submit for approval*).
* **Details panel → Workflow section** — status chip, current step, who it waits for, my
  transition buttons if I am a candidate, link to the monitor; nothing when the document has
  no instance.
* **Dashboard** — a *My tasks* card (count + first entries) when the feature is on.

All UI strings live under `workflow.*` in the 8 locales. Everything is in dedicated files
(`models/workflow.models.ts`, `services/workflow*.ts`, `pages/workflows/**`,
`dialogs/start-workflow-dialog`, `components/workflow-diagram`, `components/metadata-panel/document-workflow`)
so the enterprise fork only mirrors the descriptor entries and the route.

---

## 9. Extension points, and what the Enterprise Edition adds

| Seam (`service/workflow/`) | Core default | Enterprise |
|---|---|---|
| `WorkflowNotifier` | `NoopWorkflowNotifier` | In-app notifications (`WORKFLOW_TASK_ASSIGNED`, `WORKFLOW_TASK_OVERDUE`, `WORKFLOW_COMPLETED`, `WORKFLOW_CANCELLED`) through the bell / SSE |
| `WorkflowMailer` | `SmtpWorkflowMailer` / `LoggingWorkflowMailer` (`workflow-mail/messages_*.properties`) | unchanged |
| `WorkflowAccessPolicy` | active file / everyone / initiator | ownership & share model; monitor scoped to what the user may see |
| `WorkflowCommentBridge` | no-op | echoes each decision comment into the document's threaded comments (replies, @mentions) |
| `WorkflowActorResolver` | synthetic `JwtAuthenticationToken` from the stored e-mail (sweeper, auto-start) | resolves the `users` row |
| `AbstractSecurityService.isWorkflowAuthorized` | roles above | richer role model |

Also on the EE side: a user/team picker in the designer and the start dialog (the CE types
e-mails), webhook events on every transition, and later SLA escalation / delegation.

Follow-ups not in this version: AI/MCP tools (`startWorkflow`, `listMyTasks`, `completeTask`),
parallel approvals (N of M), conditions on metadata, and per-folder default workflows in the
upload dialog.

---

## 10. Configuration reference

| Property | Env var | Default | What it does |
|---|---|---|---|
| `openfilz.workflows.active` | `OPENFILZ_WORKFLOWS_ACTIVE` | `false` | Master switch (endpoints, sweeper, `Settings.workflowsActive`). |
| `openfilz.workflows.require-designer-role` | `OPENFILZ_WORKFLOWS_REQUIRE_DESIGNER_ROLE` | `false` | Definition writes also need the `WORKFLOW_DESIGNER` realm role (`/OPENFILZ/WORKFLOW_DESIGNER` group in groups mode). Surfaced as `Settings.workflowDesignerRoleRequired`. |
| `openfilz.workflows.web-base-url` | `OPENFILZ_WORKFLOWS_WEB_BASE_URL` | *(empty)* | Overrides `openfilz.common.web-public-base-url` for the links in workflow e-mails (`{base}workflows?task=…`). |
| `openfilz.workflows.sweep.cron` | `OPENFILZ_WORKFLOWS_SWEEP_CRON` | `0 0 * * * ?` | Overdue reminder cadence. |
| `openfilz.workflows.mail.from` / `from-name` | `OPENFILZ_WORKFLOWS_MAIL_FROM` / `_FROM_NAME` | `no-reply@openfilz.com` / `OpenFilz Workflows` | Sender of workflow e-mails. |
| `openfilz.workflows.max-states` | `OPENFILZ_WORKFLOWS_MAX_STATES` | `30` | Size guard on a definition. |

Tests: `e2e/workflow/WorkflowDefinitionIT` (CRUD + validation), `WorkflowInstanceIT`
(start / act / actions / history / cancel / reassign / role candidates / auto-start),
`WorkflowSecurityIT` (roles, non-candidate `403`, designer role), `WorkflowDisabledIT`
(everything `404`, settings flag off), `WorkflowSpecValidatorTest` (unit). The ITs capture
mails through a `@Primary` `CapturingWorkflowMailer`, like e-Sign.
