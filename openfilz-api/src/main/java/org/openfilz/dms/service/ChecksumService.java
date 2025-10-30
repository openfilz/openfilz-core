package org.openfilz.dms.service;

import reactor.core.publisher.Mono;

import org.openfilz.dms.dto.Checksum;

import java.util.HashMap;
import java.util.Map;

public interface ChecksumService {

    String HASH_SHA256 = "sha256";

    default Mono<Checksum> getChecksumMono(String storagePath, Map<String, Object> metadata, String checksum) {
        Map<String, Object> newMap;
        if(metadata == null) {
            newMap = Map.of(HASH_SHA256, checksum);
        } else {
            newMap = new HashMap<>(metadata);
            newMap.put(HASH_SHA256, checksum);
        }
        return Mono.just(new Checksum(storagePath, newMap));
    }

    Mono<Checksum> calculateChecksum(String storagePath, Map<String, Object> metadata);

}
