package org.openfilz.dms.exception;

/**
 * A stored AI API key that no configured key opens: {@code AI_SETTINGS_ENCRYPTION_KEY} was replaced and the
 * old key is not listed in {@code AI_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}, or the stored value is damaged.
 * {@link GlobalExceptionHandler} answers 409 {@code {"code":"AI_KEY_UNREADABLE"}}: the user saves the key
 * again, nothing is lost but the key itself.
 * <p>
 * Extends {@link IllegalStateException}, which it replaces, so code that already caught that keeps working.
 */
public class AiKeyUnreadableException extends IllegalStateException {

    public static final String CODE = "AI_KEY_UNREADABLE";

    public static final String USER_MESSAGE = "Your saved key can no longer be read, save it again";

    public AiKeyUnreadableException(String message) {
        super(message);
    }
}
