package org.openfilz.dms.exception;

/**
 * No chat model resolves for the caller: no personal settings, no model set at runtime by an
 * administrator, and no deployment default. An ordinary state on a deployment that ships no LLM, not
 * a server fault: {@link GlobalExceptionHandler} answers 409
 * {@code {"code":"AI_NO_MODEL","userKeysAllowed":...}} so the web apps can explain how to add one.
 * <p>
 * Extends {@link IllegalStateException}, which it replaces, so code that already caught that keeps
 * working (the AI tools turn it into a tool answer).
 */
public class AiNoModelException extends IllegalStateException {

    public static final String CODE = "AI_NO_MODEL";

    /** Whether each user may bring their own model and key ({@code openfilz.ai.user-settings.enabled}). */
    private final boolean userKeysAllowed;

    public AiNoModelException(String message, boolean userKeysAllowed) {
        super(message);
        this.userKeysAllowed = userKeysAllowed;
    }

    public boolean isUserKeysAllowed() {
        return userKeysAllowed;
    }
}
