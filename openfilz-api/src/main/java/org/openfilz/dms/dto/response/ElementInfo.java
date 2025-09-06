package org.openfilz.dms.dto.response;

import java.util.UUID;

public record ElementInfo(UUID id, String name, String type) {
}