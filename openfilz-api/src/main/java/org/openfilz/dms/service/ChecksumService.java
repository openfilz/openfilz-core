package org.openfilz.dms.service;

import org.openfilz.dms.dto.Checksum;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.openfilz.dms.utils.FileUtils.getMetadataWithChecksum;

public interface ChecksumService {

    String SHA_256 = "SHA-256";
    String HASH_SHA256_KEY = "sha256";

    default Mono<Checksum> getChecksumMono(String storagePath, Map<String, Object> metadata, String checksum) {
        Map<String, Object> newMap = getMetadataWithChecksum(metadata, checksum);
        return Mono.just(new Checksum(storagePath, newMap));
    }

    Mono<Checksum> calculateChecksum(String storagePath, Map<String, Object> metadata);

    /**
     * Calculate the checksum of the previous version of a versioned object.
     * Returns empty Mono for non-versioned storage implementations or when no previous version exists.
     *
     * @param storagePath the object path
     * @return Mono containing the hex-encoded SHA-256 checksum, or empty if unavailable
     */
    default Mono<String> calculatePreviousVersionChecksum(String storagePath) {
        return Mono.empty();
    }

}
