package org.openfilz.dms.dto.workflow;

/** {@code GET /workflows/tasks/mine/count}: the sidebar badge. */
public record MyTasksCountDTO(long count, long overdue) {
}
