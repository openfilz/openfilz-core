package org.openfilz.dms.exception;

/**
 * An unfinished TUS upload is past its expiration: nothing more is accepted for it, and what was
 * received is removed by the next clean-up. Answered with 410 Gone (TUS expiration extension), so a
 * client starts the upload again instead of retrying what can no longer succeed.
 */
public class TusUploadExpiredException extends TusUploadException {

    public TusUploadExpiredException(String uploadId) {
        super("Upload has expired: " + uploadId);
    }
}
