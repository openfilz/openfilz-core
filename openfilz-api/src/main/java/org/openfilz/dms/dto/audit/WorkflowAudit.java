package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

/** Details of the {@code WORKFLOW_*} audit actions logged on the document (docs/workflows.md §7). */
@JsonTypeName(AuditLogDetails.WORKFLOW)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.WORKFLOW)
public class WorkflowAudit extends AuditLogDetails {

    @Schema(description = "Workflow instance")
    private UUID instanceId;

    @Schema(description = "Workflow definition name")
    private String workflow;

    @Schema(description = "Status left (null on start)")
    private String fromState;

    @Schema(description = "Status entered")
    private String toState;

    @Schema(description = "Transition taken")
    private String transition;

    @Schema(description = "Decision comment")
    private String comment;
}
