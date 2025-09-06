package org.openfilz.dms.dto.response;

import java.util.UUID;

public record UploadResponse(UUID id, String name, String contentType, Long size) {
}
