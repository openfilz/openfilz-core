// com/example/dms/exception/DocumentNotFoundException.java
package org.openfilz.dms.exception;

import org.openfilz.dms.enums.DocumentType;

import java.util.UUID;

public class DocumentNotFoundException extends RuntimeException {

    private static final String DOCUMENT_NOT_FOUND = "Document not found : ";
    private static final String FILE_NOT_FOUND = "File not found : ";
    private static final String FOLDER_NOT_FOUND = "Folder not found : ";

    public DocumentNotFoundException(DocumentType type, UUID uuid) {
        this((type == DocumentType.FILE ? FILE_NOT_FOUND : FOLDER_NOT_FOUND) + uuid);
    }

    public DocumentNotFoundException(UUID uuid) {
        this(DOCUMENT_NOT_FOUND + uuid);
    }

    public DocumentNotFoundException(String message) {
        super(message);
    }
}
