package org.openfilz.dms.exception;

/**
 * Exception thrown when an uploaded file exceeds the configured quota limit.
 */
public class FileSizeExceededException extends AbstractOpenFilzException {

    public FileSizeExceededException(String filename, long fileSize, long maxSize) {
        super(String.format("File '%s' exceeds the maximum allowed size. File size: %d MB, Maximum allowed: %d MB",
                filename, fileSize / (1024 * 1024), maxSize / (1024 * 1024)));
    }

    public FileSizeExceededException(long fileSize, long maxSize) {
        super(String.format("File exceeds the maximum allowed size. File size: %d MB, Maximum allowed: %d MB",
                fileSize / (1024 * 1024), maxSize / (1024 * 1024)));
    }

    @Override
    public String getError() {
        return OpenFilzException.FILE_SIZE_EXCEEDED;
    }
}
