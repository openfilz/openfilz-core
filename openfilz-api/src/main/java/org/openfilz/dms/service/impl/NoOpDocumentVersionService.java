package org.openfilz.dms.service.impl;

import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.DownloadableVersion;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.exception.VersioningDisabledException;
import org.openfilz.dms.service.DocumentVersionService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Active when versioning is unavailable (storage.type != minio or
 * storage.minio.versioning-enabled=false): every operation fails with
 * {@link VersioningDisabledException} (HTTP 409).
 */
@Service
@Lazy
@Qualifier("versioningDisabled")
public class NoOpDocumentVersionService implements DocumentVersionService {

    @Override
    public Flux<DocumentVersionInfo> listVersions(UUID documentId) {
        return Flux.error(new VersioningDisabledException());
    }

    @Override
    public Mono<DownloadableVersion> downloadVersion(UUID documentId, String versionId) {
        return Mono.error(new VersioningDisabledException());
    }

    @Override
    public Mono<RestoreVersionResponse> restoreVersion(UUID documentId, String versionId) {
        return Mono.error(new VersioningDisabledException());
    }
}
