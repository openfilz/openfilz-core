package org.openfilz.dms.exception;

import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.DocumentType;

import static org.openfilz.dms.enums.DocumentType.FILE;

public class DuplicateNameException extends RuntimeException {
    public DuplicateNameException(String message) {
        super(message);
    }

    public DuplicateNameException(@NotNull DocumentType documentType, String documentName) {
        super("A " + ((documentType == FILE) ? "file" : "folder") + " with name '" + documentName + "' already exists in the target location.");
    }
}
