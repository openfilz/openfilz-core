package org.openfilz.dms.dto.workflow;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /workflows/instances/{id}/cancel}. */
public record CancelInstanceRequest(@Size(max = 2000) String comment) {
}
