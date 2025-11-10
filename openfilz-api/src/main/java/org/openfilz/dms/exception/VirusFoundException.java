package org.openfilz.dms.exception;

import java.util.List;

public class VirusFoundException extends RuntimeException {

    public VirusFoundException(List<String> viruses) {
        super("Virus detected in the uploaded file. Signatures: " + viruses);
    }
}