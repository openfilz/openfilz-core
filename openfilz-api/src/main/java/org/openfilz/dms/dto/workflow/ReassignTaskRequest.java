package org.openfilz.dms.dto.workflow;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body of {@code POST /workflows/tasks/{id}/reassign}. */
public record ReassignTaskRequest(@NotEmpty List<String> emails, @Size(max = 2000) String comment) {
}
