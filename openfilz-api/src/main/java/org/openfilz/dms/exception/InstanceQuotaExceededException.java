package org.openfilz.dms.exception;

import org.openfilz.dms.utils.FileUtils;

/**
 * Exception thrown when an operation would take the whole instance past its total storage quota
 * ({@code openfilz.quota.total}), whatever the caller's own quota. Answered with HTTP 507, like
 * {@link UserQuotaExceededException}, but with its own error code so a client can tell "your
 * space is full" from "the server is full — contact your administrator".
 */
public class InstanceQuotaExceededException extends AbstractOpenFilzException {

    public InstanceQuotaExceededException(long currentUsage, long newFileSize, long maxQuota) {
        super(String.format(
                "The storage of this instance is full. Current usage: %s, File size: %s, Maximum allowed: %s. Contact your administrator.",
                FileUtils.humanReadableBytes(currentUsage),
                FileUtils.humanReadableBytes(newFileSize),
                FileUtils.humanReadableBytes(maxQuota)));
    }

    @Override
    public String getError() {
        return OpenFilzException.INSTANCE_QUOTA_EXCEEDED;
    }
}
