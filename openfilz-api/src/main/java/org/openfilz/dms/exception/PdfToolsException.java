package org.openfilz.dms.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * A PDF operation could not be performed on the given input. Carries a stable {@link #code} the
 * frontend and SDKs can match on (it prefixes the error message as {@code CODE: text}) and the
 * HTTP status to answer with:
 * <ul>
 *   <li>422 — {@code NOT_A_PDF}, {@code PDF_ENCRYPTED}, {@code PDF_TOO_MANY_PAGES}, {@code PDF_NO_OUTLINE},
 *       {@code TOO_MANY_OUTPUTS}, {@code PDF_INVALID}</li>
 *   <li>409 — {@code PDF_SIGNED} (in-place edit of a signed PDF without acknowledgement),
 *       {@code ACTIVE_SIGNATURE_ENVELOPE}, {@code WORM_MODE}</li>
 *   <li>503 — {@code BUSY} (no free composition slot)</li>
 * </ul>
 */
@Getter
public class PdfToolsException extends AbstractOpenFilzException {

    public static final String NOT_A_PDF = "NOT_A_PDF";
    public static final String PDF_ENCRYPTED = "PDF_ENCRYPTED";
    public static final String PDF_INVALID = "PDF_INVALID";
    public static final String PDF_TOO_MANY_PAGES = "PDF_TOO_MANY_PAGES";
    public static final String PDF_NO_OUTLINE = "PDF_NO_OUTLINE";
    public static final String TOO_MANY_OUTPUTS = "TOO_MANY_OUTPUTS";
    public static final String PDF_SIGNED = "PDF_SIGNED";
    public static final String ACTIVE_SIGNATURE_ENVELOPE = "ACTIVE_SIGNATURE_ENVELOPE";
    public static final String WORM_MODE = "WORM_MODE";
    public static final String BUSY = "BUSY";

    private final HttpStatus status;
    private final String code;

    public PdfToolsException(String code, String message) {
        this(HttpStatus.UNPROCESSABLE_CONTENT, code, message);
    }

    public PdfToolsException(HttpStatus status, String code, String message) {
        super(code + ": " + message);
        this.status = status;
        this.code = code;
    }

    public static PdfToolsException conflict(String code, String message) {
        return new PdfToolsException(HttpStatus.CONFLICT, code, message);
    }

    /** The stable error code (also the prefix of the message), for the generic error contract. */
    @Override
    public String getError() {
        return code;
    }
}
