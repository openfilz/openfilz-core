package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Details of {@code WORKFLOW_ACTION_FAILED}: an on-enter action a workflow attempted on this
 * document and could not carry out.
 * <p>
 * A failed action never blocks the transition — the document moves on regardless — so without this
 * entry the trail would show the transition and simply <em>no move</em>, with nothing to say one was
 * meant to happen. The successful case needs no entry of its own: the action writes its own
 * (MOVE_FILE, UPDATE_DOCUMENT_METADATA…), marked with the workflow via {@link AuditLogDetails}.
 * <p>
 * The workflow, instance and status live in the inherited fields; the actor is, as always, the
 * person the workflow acted for.
 */
@JsonTypeName(AuditLogDetails.WORKFLOW_ACTION_FAILURE)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.WORKFLOW_ACTION_FAILURE)
public class WorkflowActionFailureAudit extends AuditLogDetails {

    @Schema(description = "Action that failed (MOVE_TO_FOLDER, SET_METADATA, NOTIFY)")
    private String action;

    @Schema(description = "What it targeted: the destination folder, the metadata keys, the recipients")
    private String target;

    @Schema(description = "Why it failed")
    private String error;
}
