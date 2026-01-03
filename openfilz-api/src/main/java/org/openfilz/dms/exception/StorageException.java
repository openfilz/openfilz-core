package org.openfilz.dms.exception;

public class StorageException extends AbstractOpenFilzException {
    public StorageException(Throwable cause) {
        super(cause);
    }

    @Override
    public String getError() {
        return OpenFilzException.STORAGE;
    }

    public StorageException(String message) {
        super(message);
    }

    public StorageException(String message, Throwable cause) {
        super(message, cause);
    }
}