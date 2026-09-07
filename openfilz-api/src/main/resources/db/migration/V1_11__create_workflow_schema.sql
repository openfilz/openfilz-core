-- ============================================================================
-- V1_11 — Workflows (statuses + transitions + tasks) — Community Edition
--
-- A small native state machine attached to one document. See docs/workflows.md.
-- Every statement is idempotent (enterprise databases may already carry parts
-- of this schema after a submodule bump).
--
-- Model:
--   workflow_definition      named, versioned spec (JSON: states, transitions, actions)
--   workflow_instance        one document going through one definition (spec snapshotted)
--   workflow_task            "document X is in status S and waits for one of these people"
--   workflow_task_candidate  one row per candidate e-mail of an open task
--   workflow_event           append-only history rendered as the timeline
-- ============================================================================

CREATE TABLE IF NOT EXISTS workflow_definition (
    id                   UUID         PRIMARY KEY,
    name                 VARCHAR(100) NOT NULL,
    description          VARCHAR(1000),
    active               BOOLEAN      NOT NULL DEFAULT TRUE,
    spec                 JSONB        NOT NULL,
    trigger_folder_ids   JSONB,
    version              INTEGER      NOT NULL DEFAULT 1,
    created_by           VARCHAR(255) NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL
);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wf_definition_name ON workflow_definition (lower(name));

CREATE TABLE IF NOT EXISTS workflow_instance (
    id                   UUID         PRIMARY KEY,
    definition_id        UUID         NOT NULL,
    definition_name      VARCHAR(100) NOT NULL,
    definition_version   INTEGER      NOT NULL DEFAULT 1,
    spec                 JSONB        NOT NULL,
    document_id          UUID         NOT NULL,
    document_name        VARCHAR(255) NOT NULL,
    status               VARCHAR(16)  NOT NULL,          -- RUNNING | COMPLETED | CANCELLED
    current_state_key    VARCHAR(40)  NOT NULL,
    current_state_label  VARCHAR(100) NOT NULL,
    started_by           VARCHAR(255) NOT NULL,
    assignments          JSONB,
    locale               VARCHAR(8),
    started_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    completed_at         TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_wf_instance_document ON workflow_instance (document_id);
CREATE INDEX IF NOT EXISTS idx_wf_instance_definition ON workflow_instance (definition_id);
CREATE INDEX IF NOT EXISTS idx_wf_instance_status_started ON workflow_instance (status, started_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS idx_wf_instance_one_running_per_document
    ON workflow_instance (document_id) WHERE status = 'RUNNING';

CREATE TABLE IF NOT EXISTS workflow_task (
    id                   UUID         PRIMARY KEY,
    instance_id          UUID         NOT NULL REFERENCES workflow_instance(id) ON DELETE CASCADE,
    state_key            VARCHAR(40)  NOT NULL,
    state_label          VARCHAR(100) NOT NULL,
    candidate_role       VARCHAR(64),
    status               VARCHAR(16)  NOT NULL,          -- OPEN | DONE | CANCELLED
    due_at               TIMESTAMPTZ,
    reminded_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL,
    completed_at         TIMESTAMPTZ,
    completed_by         VARCHAR(255),
    transition_key       VARCHAR(40),
    comment              VARCHAR(2000)
);
CREATE INDEX IF NOT EXISTS idx_wf_task_instance ON workflow_task (instance_id);
CREATE INDEX IF NOT EXISTS idx_wf_task_open ON workflow_task (status, due_at);
CREATE INDEX IF NOT EXISTS idx_wf_task_role ON workflow_task (candidate_role) WHERE status = 'OPEN';

CREATE TABLE IF NOT EXISTS workflow_task_candidate (
    task_id              UUID         NOT NULL REFERENCES workflow_task(id) ON DELETE CASCADE,
    email                VARCHAR(255) NOT NULL,
    PRIMARY KEY (task_id, email)
);
CREATE INDEX IF NOT EXISTS idx_wf_task_candidate_email ON workflow_task_candidate (email);

CREATE TABLE IF NOT EXISTS workflow_event (
    id                   UUID         PRIMARY KEY,
    instance_id          UUID         NOT NULL REFERENCES workflow_instance(id) ON DELETE CASCADE,
    event_type           VARCHAR(32)  NOT NULL,          -- STARTED | TRANSITIONED | ACTION_APPLIED | ACTION_FAILED | REASSIGNED | REMINDED | COMPLETED | CANCELLED
    from_state           VARCHAR(40),
    to_state             VARCHAR(40),
    transition_key       VARCHAR(40),
    actor                VARCHAR(255),
    comment              VARCHAR(2000),
    details              JSONB,
    created_at           TIMESTAMPTZ  NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_wf_event_instance ON workflow_event (instance_id, created_at);

COMMENT ON TABLE workflow_definition IS 'Workflows: a named state machine (statuses, transitions, on-enter actions) stored as JSON';
COMMENT ON TABLE workflow_instance IS 'Workflows: one document going through one definition; the spec is snapshotted at start';
COMMENT ON TABLE workflow_task IS 'Workflows: the pending decision of one status; closed by the transition a candidate picks';
COMMENT ON TABLE workflow_task_candidate IS 'Workflows: the e-mails that may complete a task (any of them)';
COMMENT ON TABLE workflow_event IS 'Workflows: append-only history of an instance (timeline)';
