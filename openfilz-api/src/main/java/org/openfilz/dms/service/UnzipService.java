package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.UnzipRequest;
import org.openfilz.dms.dto.response.UnzipResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/** Server-side extraction of a ZIP document into a folder of the DMS. */
public interface UnzipService {

    /**
     * Extracts the ZIP document {@code zipId} into the destination described by {@code request},
     * recreating its folder tree. Each extracted file is a regular upload (audit, quotas,
     * post-processing), each folder a regular folder creation.
     */
    Mono<UnzipResponse> unzip(UUID zipId, UnzipRequest request);
}
