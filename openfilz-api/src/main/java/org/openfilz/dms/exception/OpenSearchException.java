package org.openfilz.dms.exception;

import java.io.IOException;

public class OpenSearchException extends RuntimeException {
    public OpenSearchException(IOException e) {
        super(e);
    }
}
