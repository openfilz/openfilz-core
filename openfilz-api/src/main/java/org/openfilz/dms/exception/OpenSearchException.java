package org.openfilz.dms.exception;

import java.io.IOException;

public class OpenSearchException extends AbstractOpenFilzException {
    public OpenSearchException(IOException e) {
        super(e);
    }

    @Override
    public String getError() {
        return OpenFilzException.OPENSEARCH;
    }
}
