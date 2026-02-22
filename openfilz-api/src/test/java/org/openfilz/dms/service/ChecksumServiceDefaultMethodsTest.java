package org.openfilz.dms.service;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.Checksum;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChecksumServiceDefaultMethodsTest {

    private final ChecksumService checksumService = new ChecksumService() {
        @Override
        public Mono<Checksum> calculateChecksum(String storagePath, Map<String, Object> metadata) {
            return Mono.empty();
        }
    };

    @Test
    void getChecksumMono_withNullMetadata_createsNewMapWithChecksum() {
        StepVerifier.create(checksumService.getChecksumMono("path/file.txt", null, "abc123"))
                .expectNextMatches(checksum -> {
                    assertEquals("path/file.txt", checksum.storagePath());
                    assertEquals("abc123", checksum.metadataWithChecksum().get("sha256"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void getChecksumMono_withExistingMetadata_addsChecksum() {
        Map<String, Object> existing = Map.of("key", "value");

        StepVerifier.create(checksumService.getChecksumMono("path/file.txt", existing, "def456"))
                .expectNextMatches(checksum -> {
                    assertEquals("path/file.txt", checksum.storagePath());
                    assertEquals("def456", checksum.metadataWithChecksum().get("sha256"));
                    assertEquals("value", checksum.metadataWithChecksum().get("key"));
                    return true;
                })
                .verifyComplete();
    }

    @Test
    void calculatePreviousVersionChecksum_returnsEmptyMono() {
        StepVerifier.create(checksumService.calculatePreviousVersionChecksum("any/path"))
                .verifyComplete();
    }
}
