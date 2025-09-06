package org.openfilz.dms.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RenameRequest(@NotBlank @Size(min = 1, max = 255) String newName) {
}
