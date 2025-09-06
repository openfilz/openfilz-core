package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.openfilz.dms.dto.request.CreateFolderRequest;

import java.util.UUID;

@JsonTypeName(AuditLogDetails.CREATE_FOLDER)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.CREATE_FOLDER)
public class CreateFolderAudit extends AuditLogDetails {

    public CreateFolderAudit(CreateFolderRequest request) {
        this(request, null  );
    }

    private CreateFolderRequest request;
    private UUID sourceFolderId;

}
