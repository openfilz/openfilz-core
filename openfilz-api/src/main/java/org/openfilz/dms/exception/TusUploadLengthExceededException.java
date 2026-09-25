package org.openfilz.dms.exception;

/**
 * A TUS PATCH would write past the {@code Upload-Length} declared when the upload was created —
 * the length every quota was checked against. Answered with HTTP 413; nothing beyond the declared
 * length is ever kept.
 */
public class TusUploadLengthExceededException extends TusUploadException {

    public TusUploadLengthExceededException(long offset, long chunkLength, long uploadLength) {
        super(String.format("Chunk exceeds the declared Upload-Length: offset %d + %d bytes > %d",
                offset, chunkLength, uploadLength));
    }

    public TusUploadLengthExceededException(long uploadLength) {
        super("Chunk exceeds the declared Upload-Length of " + uploadLength + " bytes");
    }

    @Override
    public String getError() {
        return OpenFilzException.FILE_SIZE_EXCEEDED;
    }
}
