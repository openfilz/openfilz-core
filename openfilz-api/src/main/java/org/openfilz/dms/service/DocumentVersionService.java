package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.DownloadableVersion;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Version operations on file documents, backed by storage-level object versioning
 * (MinIO/S3 bucket versioning).
 * <p>
 * The active implementation is selected at runtime by
 * {@link org.openfilz.dms.config.DocumentVersionConfig}: when {@code storage.type=minio}
 * and {@code storage.minio.versioning-enabled=true} the real implementation is used,
 * otherwise every operation fails with
 * {@link org.openfilz.dms.exception.VersioningDisabledException} (HTTP 409).
 */
public interface DocumentVersionService {

    /**
     * List all surviving versions of a file document, newest first.
     */
    Flux<DocumentVersionInfo> listVersions(UUID documentId);

    /**
     * Load the content of a specific version of a file document.
     */
    Mono<DownloadableVersion> downloadVersion(UUID documentId, String versionId);

    /**
     * Restore a previous version as the new latest version (history-preserving:
     * a new version is created, nothing is deleted) and log a
     * {@code RESTORE_DOCUMENT_VERSION} audit entry.
     */
    Mono<RestoreVersionResponse> restoreVersion(UUID documentId, String versionId);
}
