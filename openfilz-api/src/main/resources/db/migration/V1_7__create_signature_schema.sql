-- ============================================================================
-- V1_7 — e-Sign (electronic signature) schema — Community Edition
--
-- Moves the envelope engine into the open-source core (see
-- openfilz-enterprise/docs/esign-ce-ee-split.md). Every statement is idempotent
-- because Enterprise databases created the three base tables earlier through
-- V3_13 (collaboration-db-model); on those databases this migration only adds
-- the new columns / tables and back-fills signature_field.
--
-- Model:
--   signature_envelope   one source PDF sent to N recipients (parallel or sequential)
--   signature_recipient  a signer (or CC) with a tokenized link; optional OTP
--   signature_field      N typed fields per recipient (signature, initials, date, text, ...)
--   signature_template   reusable roles + fields definition
--   signature_event      append-only trail rendered into the Certificate of Completion
-- ============================================================================

CREATE TABLE IF NOT EXISTS signature_envelope (
    id                   UUID         PRIMARY KEY,
    tenant_id            UUID         NOT NULL,
    initiator_id         UUID         NOT NULL,
    initiator_email      VARCHAR(255) NOT NULL,
    title                VARCHAR(255) NOT NULL,
    message              VARCHAR(2000),
    source_doc_id        UUID         NOT NULL,
    signed_doc_id        UUID,
    signed_storage_path  VARCHAR(1024),
    original_sha256      VARCHAR(64),
    signed_sha256        VARCHAR(64),
    status               VARCHAR(16)  NOT NULL,
    created_at           TIMESTAMPTZ  NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    completed_at         TIMESTAMPTZ,
    cancelled_at         TIMESTAMPTZ,
    expires_at           TIMESTAMPTZ  NOT NULL
);

CREATE TABLE IF NOT EXISTS signature_recipient (
    id                   UUID         PRIMARY KEY,
    envelope_id          UUID         NOT NULL REFERENCES signature_envelope(id) ON DELETE CASCADE,
    user_id              UUID,
    recipient_name       VARCHAR(255),
    recipient_email      VARCHAR(255) NOT NULL,
    order_index          INTEGER      NOT NULL DEFAULT 0,
    status               VARCHAR(16)  NOT NULL,
    token_hash           VARCHAR(64)  NOT NULL,
    field_page           INTEGER,
    field_x              DOUBLE PRECISION,
    field_y              DOUBLE PRECISION,
    field_w              DOUBLE PRECISION,
    field_h              DOUBLE PRECISION,
    signature_image      TEXT,
    signature_typed      VARCHAR(255),
    viewed_at            TIMESTAMPTZ,
    signed_at            TIMESTAMPTZ,
    signer_ip            VARCHAR(64),
    signer_user_agent    VARCHAR(512),
    decline_reason       VARCHAR(1000)
);

CREATE TABLE IF NOT EXISTS signature_event (
    id                   UUID         PRIMARY KEY,
    envelope_id          UUID         NOT NULL REFERENCES signature_envelope(id) ON DELETE CASCADE,
    event_type           VARCHAR(32)  NOT NULL,
    actor                VARCHAR(255),
    doc_sha256           VARCHAR(64),
    signer_ip            VARCHAR(64),
    details              VARCHAR(2000),
    created_at           TIMESTAMPTZ  NOT NULL
);

-- ── Envelope: sequential signing, templates, reminders, drafts ───────────────
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS sequential       BOOLEAN     NOT NULL DEFAULT FALSE;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS current_order    INTEGER     NOT NULL DEFAULT 0;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS template_id      UUID;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS reminder_days    INTEGER;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS last_reminded_at TIMESTAMPTZ;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS locale           VARCHAR(8);
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS sent_at          TIMESTAMPTZ;
ALTER TABLE signature_envelope ADD COLUMN IF NOT EXISTS seal_provider    VARCHAR(32);

-- ── Recipient: role, OTP, reminders, token revocation ───────────────────────
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS role            VARCHAR(16)  NOT NULL DEFAULT 'SIGNER';
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS auth_method     VARCHAR(16)  NOT NULL DEFAULT 'NONE';
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS phone           VARCHAR(32);
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_hash        VARCHAR(64);
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_expires_at  TIMESTAMPTZ;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_attempts    INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_verified_at TIMESTAMPTZ;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS locale          VARCHAR(8);
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS reminder_count  INTEGER      NOT NULL DEFAULT 0;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS token_revoked   BOOLEAN      NOT NULL DEFAULT FALSE;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS sort_order      INTEGER      NOT NULL DEFAULT 0;  -- position in the request

-- ── Fields: N typed fields per recipient ─────────────────────────────────────
CREATE TABLE IF NOT EXISTS signature_field (
    id            UUID         PRIMARY KEY,
    envelope_id   UUID         NOT NULL REFERENCES signature_envelope(id) ON DELETE CASCADE,
    recipient_id  UUID         NOT NULL REFERENCES signature_recipient(id) ON DELETE CASCADE,
    -- SIGNATURE | INITIALS | DATE_SIGNED | TEXT | NUMBER | EMAIL | PHONE | CHECKBOX | RADIO | SELECT | IMAGE | STAMP
    type          VARCHAR(16)  NOT NULL,
    page          INTEGER      NOT NULL,
    x             DOUBLE PRECISION NOT NULL,
    y             DOUBLE PRECISION NOT NULL,
    w             DOUBLE PRECISION NOT NULL,
    h             DOUBLE PRECISION NOT NULL,
    required      BOOLEAN      NOT NULL DEFAULT TRUE,
    label         VARCHAR(255),
    options       JSONB,                       -- RADIO / SELECT choices, RADIO group name, etc.
    value         TEXT,                        -- typed text / date / number / checkbox ("true") / radio / select
    value_image   TEXT,                        -- base64 PNG (SIGNATURE / INITIALS / IMAGE / STAMP)
    filled_at     TIMESTAMPTZ,
    sort_order    INTEGER      NOT NULL DEFAULT 0
);

-- ── Templates ────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS signature_template (
    id              UUID         PRIMARY KEY,
    owner_email     VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     VARCHAR(2000),
    source_doc_id   UUID,                      -- optional default document
    roles           JSONB        NOT NULL,     -- [{"name":"Client","orderIndex":0,"authMethod":"NONE"}]
    fields          JSONB        NOT NULL,     -- [{"role":"Client","type":"SIGNATURE","page":0,"x":..,"y":..,"w":..,"h":..,"required":true,"label":..,"options":{..}}]
    message         VARCHAR(2000),
    expires_in_days INTEGER,
    sequential      BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL
);

-- ── Indexes ──────────────────────────────────────────────────────────────────
CREATE INDEX IF NOT EXISTS idx_sig_env_initiator       ON signature_envelope(initiator_id, status);
CREATE INDEX IF NOT EXISTS idx_sig_env_initiator_email ON signature_envelope(initiator_email, status);
CREATE INDEX IF NOT EXISTS idx_sig_env_source_doc      ON signature_envelope(source_doc_id);
CREATE INDEX IF NOT EXISTS idx_sig_env_status_expires  ON signature_envelope(status, expires_at);
CREATE INDEX IF NOT EXISTS idx_sig_rcpt_envelope       ON signature_recipient(envelope_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_sig_rcpt_token   ON signature_recipient(token_hash);
CREATE INDEX IF NOT EXISTS idx_sig_rcpt_email_status   ON signature_recipient(recipient_email, status);
CREATE INDEX IF NOT EXISTS idx_sig_event_envelope      ON signature_event(envelope_id, created_at);
CREATE INDEX IF NOT EXISTS idx_sig_field_envelope      ON signature_field(envelope_id);
CREATE INDEX IF NOT EXISTS idx_sig_field_recipient     ON signature_field(recipient_id);
CREATE INDEX IF NOT EXISTS idx_sig_tpl_owner           ON signature_template(owner_email, updated_at);

-- ── Back-fill: one SIGNATURE field per legacy recipient placement ────────────
INSERT INTO signature_field (id, envelope_id, recipient_id, type, page, x, y, w, h, required,
                             value, value_image, filled_at, sort_order)
SELECT gen_random_uuid(), r.envelope_id, r.id, 'SIGNATURE', r.field_page, r.field_x, r.field_y,
       r.field_w, r.field_h, TRUE, r.signature_typed, r.signature_image, r.signed_at, 0
FROM signature_recipient r
WHERE r.field_page IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM signature_field f WHERE f.recipient_id = r.id);

COMMENT ON TABLE signature_envelope IS
    'e-Sign envelope: a source PDF dispatched to recipients for electronic signature (CE core).';
COMMENT ON TABLE signature_recipient IS
    'A signer (or CC) on an envelope. user_id NULL = external signer (token-only auth, optional OTP).';
COMMENT ON TABLE signature_field IS
    'A typed field placed for one recipient (normalized 0..1 coordinates, PDF origin bottom-left).';
COMMENT ON TABLE signature_template IS
    'Reusable envelope definition: named roles + their fields, instantiated with concrete recipients.';
COMMENT ON TABLE signature_event IS
    'Append-only audit trail rendered into the final PDF Certificate of Completion.';
