package org.openfilz.dms.exception;

import java.util.List;

public class VirusFoundException extends AbstractOpenFilzException {

    public VirusFoundException(List<String> viruses) {
        super("Virus detected in the uploaded file. Signatures: " + viruses);
    }

    @Override
    public String getError() {
        return OpenFilzException.VIRUS_FOUND;
    }
}