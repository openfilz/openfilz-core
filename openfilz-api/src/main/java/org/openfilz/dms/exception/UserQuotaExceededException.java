package org.openfilz.dms.exception;

import org.openfilz.dms.utils.FileUtils;

/**
 * Exception thrown when a user's total storage exceeds their configured quota limit.
 */
public class UserQuotaExceededException extends AbstractOpenFilzException {

    public UserQuotaExceededException(String username, long currentUsage, long newFileSize, long maxQuota) {
        super(String.format(
                "User '%s' storage quota exceeded. Current usage: %s, File size: %s, Maximum allowed: %s",
                username,
                FileUtils.humanReadableBytes(currentUsage),
                FileUtils.humanReadableBytes(newFileSize),
                FileUtils.humanReadableBytes(maxQuota)));
    }

    @Override
    public String getError() {
        return OpenFilzException.USER_QUOTA_EXCEEDED;
    }


}
