package org.openfilz.dms.exception;

public abstract class AbstractOpenFilzException extends RuntimeException {

    public AbstractOpenFilzException(String message) {
        super(message);
    }

    public AbstractOpenFilzException(String message, Throwable cause) {
        super(message, cause);
    }

    public AbstractOpenFilzException(Throwable cause) {
        super(cause);
    }

    public abstract String getError();

}
