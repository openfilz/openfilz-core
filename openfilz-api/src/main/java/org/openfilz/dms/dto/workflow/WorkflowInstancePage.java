package org.openfilz.dms.dto.workflow;

import java.util.List;

public record WorkflowInstancePage(List<WorkflowInstanceDTO> items, long total, int page, int size) {
}
