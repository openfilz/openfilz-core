package org.openfilz.dms.dto;

import java.util.Map;

public record Checksum(String storagePath, Map<String, Object> metadataWithChecksum) {}