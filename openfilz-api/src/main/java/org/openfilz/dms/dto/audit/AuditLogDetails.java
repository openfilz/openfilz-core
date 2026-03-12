package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY, property = AuditLogDetails.DISCRIMINATOR)
@Schema(requiredProperties = {AuditLogDetails.DISCRIMINATOR})
@JsonSubTypes({

        @JsonSubTypes.Type(value = CopyAudit.class, name = AuditLogDetails.COPY),
        @JsonSubTypes.Type(value = CreateFolderAudit.class, name = AuditLogDetails.CREATE_FOLDER),
        @JsonSubTypes.Type(value = DeleteAudit.class, name = AuditLogDetails.DELETE),
        @JsonSubTypes.Type(value = DeleteMetadataAudit.class, name = AuditLogDetails.DELETE_METADATA),
        @JsonSubTypes.Type(value = MoveAudit.class, name = AuditLogDetails.MOVE),
        @JsonSubTypes.Type(value = RenameAudit.class, name = AuditLogDetails.RENAME),
        @JsonSubTypes.Type(value = ReplaceAudit.class, name = AuditLogDetails.REPLACE),
        @JsonSubTypes.Type(value = UpdateMetadataAudit.class, name = AuditLogDetails.UPDATE_METADATA),
        @JsonSubTypes.Type(value = UploadAudit.class, name = AuditLogDetails.UPLOAD)

})
public abstract class AuditLogDetails implements IAuditLogDetails {

    public static final String DISCRIMINATOR = "type";

    public static final String COPY = "copy";
    public static final String CREATE_FOLDER = "createFolder";
    public static final String DELETE = "delete";
    public static final String DELETE_METADATA = "deleteMetadata";
    public static final String MOVE = "move";
    public static final String RENAME = "rename";
    public static final String REPLACE = "replace";
    public static final String UPDATE_METADATA = "updateMetadata";
    public static final String UPLOAD = "upload";

    @JsonInclude(JsonInclude.Include.ALWAYS)
    private String type;

    protected AuditLogDetails() {
        JsonTypeName annotation = this.getClass().getAnnotation(JsonTypeName.class);
        if (annotation != null) {
            this.type = annotation.value();
        }
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }
}
