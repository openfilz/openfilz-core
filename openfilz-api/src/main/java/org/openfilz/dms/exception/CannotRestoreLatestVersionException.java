package org.openfilz.dms.exception;

/**
 * Thrown when a restore targets the version that is already the current latest one.
 * Mapped to HTTP 400.
 */
public class CannotRestoreLatestVersionException extends RuntimeException {

    public CannotRestoreLatestVersionException(String versionId) {
        super("Cannot restore the current latest version : " + versionId);
    }
}
