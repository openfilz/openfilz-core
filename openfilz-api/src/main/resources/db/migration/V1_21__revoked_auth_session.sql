-- ============================================================================
-- V1_21: Keycloak sessions that were signed out
--
-- When Keycloak ends a session (a password change signing out the other
-- devices, a sign-out from the account console, an admin ending it) it sends an
-- OIDC back-channel logout to the API. The access tokens that session already
-- holds are still validly signed until they expire, so the API records the
-- session id (the "sid" claim) and refuses any token that carries it.
--
-- A row only has to outlive the longest access token Keycloak may have issued
-- for that session; expires_at is the time after which it can go.
-- ============================================================================

CREATE TABLE IF NOT EXISTS revoked_auth_session (
    sid        VARCHAR(255) PRIMARY KEY,
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_revoked_auth_session_expires_at
    ON revoked_auth_session (expires_at);
