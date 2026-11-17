package org.openfilz.dms.enums;

/**
 * Where the chat model of a caller comes from, in order of precedence.
 * Surfaced as {@code Settings.aiModelSource}.
 */
public enum AiModelSource {
    /** The caller's own provider and key (per-user settings). */
    PERSONAL,
    /** A model an administrator set at runtime for every user. */
    ORGANISATION,
    /** The deployment's configuration ({@code OPENFILZ_AI_MODEL}, provider switches, fallback chain). */
    DEPLOYMENT,
    /** No model resolves for this caller. */
    NONE
}
