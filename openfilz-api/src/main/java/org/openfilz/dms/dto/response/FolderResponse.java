package org.openfilz.dms.dto.response;

import java.util.UUID;

public record FolderResponse(UUID id, String name, UUID parentId) {
}