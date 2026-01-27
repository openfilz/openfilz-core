package org.openfilz.dms.exception;

public interface OpenFilzException {

    String AUDIT = "Audit";
    String DOCUMENT_NOT_FOUND = "DocumentNotFound";
    String DUPLICATE_NAME = "DuplicateName";
    String FILE_SIZE_EXCEEDED = "FileSizeExceeded";
    String OPENSEARCH = "OpenSearch";
    String OPERATION_FORBIDDEN = "OperationForbidden";
    String STORAGE = "Storage";
    String USER_QUOTA_EXCEEDED = "UserQuotaExceeded";
    String VIRUS_FOUND = "VirusFound";
}
