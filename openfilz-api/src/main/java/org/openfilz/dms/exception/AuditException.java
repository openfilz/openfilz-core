package org.openfilz.dms.exception;

public class AuditException extends AbstractOpenFilzException {
    public AuditException(String message, Exception e) {
        super(message, e);
    }


    @Override
    public String getError() {
        return OpenFilzException.AUDIT;
    }
}
