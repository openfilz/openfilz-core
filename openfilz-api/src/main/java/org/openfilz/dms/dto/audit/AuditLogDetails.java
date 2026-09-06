package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import io.swagger.v3.oas.annotations.media.DiscriminatorMapping;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = AuditLogDetails.DISCRIMINATOR)
@JsonSubTypes({

        @JsonSubTypes.Type(value = CopyAudit.class, name = AuditLogDetails.COPY),
        @JsonSubTypes.Type(value = CreateFolderAudit.class, name = AuditLogDetails.CREATE_FOLDER),
        @JsonSubTypes.Type(value = DeleteAudit.class, name = AuditLogDetails.DELETE),
        @JsonSubTypes.Type(value = DeleteMetadataAudit.class, name = AuditLogDetails.DELETE_METADATA),
        @JsonSubTypes.Type(value = MoveAudit.class, name = AuditLogDetails.MOVE),
        @JsonSubTypes.Type(value = RenameAudit.class, name = AuditLogDetails.RENAME),
        @JsonSubTypes.Type(value = ReplaceAudit.class, name = AuditLogDetails.REPLACE),
        @JsonSubTypes.Type(value = RestoreVersionAudit.class, name = AuditLogDetails.RESTORE_VERSION),
        @JsonSubTypes.Type(value = UpdateMetadataAudit.class, name = AuditLogDetails.UPDATE_METADATA),
        @JsonSubTypes.Type(value = UploadAudit.class, name = AuditLogDetails.UPLOAD),
        @JsonSubTypes.Type(value = PdfTransformAudit.class, name = AuditLogDetails.PDF_TRANSFORM),
        @JsonSubTypes.Type(value = WorkflowAudit.class, name = AuditLogDetails.WORKFLOW),
        @JsonSubTypes.Type(value = WorkflowActionFailureAudit.class, name = AuditLogDetails.WORKFLOW_ACTION_FAILURE)

})
@Schema(
        discriminatorProperty = AuditLogDetails.DISCRIMINATOR,
        discriminatorMapping = {
                @DiscriminatorMapping(value = AuditLogDetails.COPY, schema = CopyAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.CREATE_FOLDER, schema = CreateFolderAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.DELETE, schema = DeleteAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.DELETE_METADATA, schema = DeleteMetadataAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.MOVE, schema = MoveAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.RENAME, schema = RenameAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.REPLACE, schema = ReplaceAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.RESTORE_VERSION, schema = RestoreVersionAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.UPDATE_METADATA, schema = UpdateMetadataAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.UPLOAD, schema = UploadAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.PDF_TRANSFORM, schema = PdfTransformAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.WORKFLOW, schema = WorkflowAudit.class),
                @DiscriminatorMapping(value = AuditLogDetails.WORKFLOW_ACTION_FAILURE, schema = WorkflowActionFailureAudit.class)
        }
)
public abstract class AuditLogDetails implements IAuditLogDetails {

    /**
     * When an action was performed <em>by a workflow</em>, these say which one — on the entry the
     * action itself produced (a MOVE_FILE, an UPDATE_DOCUMENT_METADATA…), not on a separate row.
     * <p>
     * The actor stays the person who caused it: whoever took the transition, or the uploader for a
     * hot folder. That is what an audit trail must record. But without this, that person's move is
     * indistinguishable from one they made by hand — so the trail also has to say the workflow did
     * it, in which status. Stamped by {@code AuditServiceImpl} from the reactive context the
     * workflow engine writes around its on-enter actions; {@code null} on every manual action, and
     * omitted from the JSON.
     */
    @Getter @Setter
    @Schema(description = "Workflow instance this action was part of, when a workflow caused it")
    private UUID workflowInstanceId;

    @Getter @Setter
    @Schema(description = "Name of the workflow that caused this action")
    private String workflow;

    @Getter @Setter
    @Schema(description = "Label of the workflow status whose entry caused this action")
    private String workflowState;

    public static final String DISCRIMINATOR = "type";

    public static final String COPY = "copy";
    public static final String CREATE_FOLDER = "createFolder";
    public static final String DELETE = "delete";
    public static final String DELETE_METADATA = "deleteMetadata";
    public static final String MOVE = "move";
    public static final String RENAME = "rename";
    public static final String REPLACE = "replace";
    public static final String RESTORE_VERSION = "restoreVersion";
    public static final String UPDATE_METADATA = "updateMetadata";
    public static final String UPLOAD = "upload";
    public static final String PDF_TRANSFORM = "pdfTransform";
    public static final String WORKFLOW = "workflow";
    public static final String WORKFLOW_ACTION_FAILURE = "workflowActionFailure";
}
