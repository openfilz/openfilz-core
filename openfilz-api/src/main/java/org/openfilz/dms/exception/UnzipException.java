package org.openfilz.dms.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A ZIP document could not be extracted as a whole. Carries a stable {@link #code} (also the
 * prefix of the message, {@code CODE: text}) and the HTTP status to answer with:
 * <ul>
 *   <li>422 — {@code NOT_A_ZIP}, {@code ZIP_INVALID}</li>
 *   <li>413 — {@code ZIP_TOO_MANY_ENTRIES}, {@code ZIP_TOO_LARGE}, {@code ZIP_BOMB}</li>
 * </ul>
 */
@Getter
public class UnzipException extends AbstractOpenFilzException {

    public static final String NOT_A_ZIP = "NOT_A_ZIP";
    public static final String ZIP_INVALID = "ZIP_INVALID";
    public static final String ZIP_TOO_MANY_ENTRIES = "ZIP_TOO_MANY_ENTRIES";
    public static final String ZIP_TOO_LARGE = "ZIP_TOO_LARGE";
    public static final String ZIP_BOMB = "ZIP_BOMB";

    private final HttpStatus status;
    private final String code;

    public UnzipException(String code, String message) {
        this(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public UnzipException(HttpStatus status, String code, String message) {
        super(code + ": " + message);
        this.status = status;
        this.code = code;
    }

    public static UnzipException tooLarge(String code, String message) {
        return new UnzipException(HttpStatus.CONTENT_TOO_LARGE, code, message);
    }

    /** The stable error code (also the prefix of the message), for the generic error contract. */
    @Override
    public String getError() {
        return code;
    }
}
