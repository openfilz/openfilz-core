package org.openfilz.dms.exception;

public class OperationForbiddenException extends AbstractOpenFilzException {
    public OperationForbiddenException(String message) {
        super(message);
    }

    @Override
    public String getError() {
        return OpenFilzException.OPERATION_FORBIDDEN;
    }
}
