package org.openfilz.dms.dto.workflow;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /workflows/tasks/{id}/complete}. */
public record CompleteTaskRequest(@NotBlank String transitionKey, @Size(max = 2000) String comment) {
}
