package org.openfilz.dms.exception;

/**
 * Exception thrown when a TUS upload operation fails.
 */
public class TusUploadException extends AbstractOpenFilzException {

    private static final String ERROR_TYPE = "TusUploadError";

    public TusUploadException(String message) {
        super(message);
    }

    public TusUploadException(String message, Throwable cause) {
        super(message, cause);
    }

    @Override
    public String getError() {
        return ERROR_TYPE;
    }
}
