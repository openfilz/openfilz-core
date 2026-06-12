package org.openfilz.dms.exception;

/**
 * Thrown when a version-related operation is requested but file versioning is not
 * available (storage.type is not minio, or storage.minio.versioning-enabled is false).
 * Mapped to HTTP 409 so clients can distinguish "feature off" from "version not found" (404).
 */
public class VersioningDisabledException extends RuntimeException {

    public VersioningDisabledException() {
        super("File versioning is not enabled on this server");
    }
}
