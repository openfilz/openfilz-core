-- ============================================================================
-- V1_17 — e-Sign: per-link throttle on one-time-code requests
--
-- A signing link could ask for an unlimited number of OTP codes back to back
-- (each one a mail / SMS), and every request reset the failed-attempt counter.
-- The two columns below let the service enforce a cooldown between requests and
-- a lifetime cap per token (both reset when the token is re-issued).
-- Idempotent: Enterprise databases share the table.
-- ============================================================================

ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_requested_at  TIMESTAMPTZ;
ALTER TABLE signature_recipient ADD COLUMN IF NOT EXISTS otp_request_count INTEGER NOT NULL DEFAULT 0;

COMMENT ON COLUMN signature_recipient.otp_requested_at IS
    'When the last one-time code was issued for the current link (request cooldown).';
COMMENT ON COLUMN signature_recipient.otp_request_count IS
    'One-time codes issued for the current link (lifetime cap); reset when the token is re-issued.';
