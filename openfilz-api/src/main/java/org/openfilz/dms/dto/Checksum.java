package org.openfilz.dms.dto;

import org.openfilz.dms.service.ChecksumService;

import java.util.Map;

public record Checksum(String storagePath, Map<String, Object> metadataWithChecksum) {
    public String hash() {
        return metadataWithChecksum.get(ChecksumService.HASH_SHA256_KEY).toString();
    }
}