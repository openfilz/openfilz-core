package org.openfilz.dms.dto.audit;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonTypeName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.openfilz.dms.entity.SignatureEnvelope;

import java.util.UUID;

/**
 * Which e-Sign envelope a {@code SIGNATURE_*} audit entry belongs to. Without it, a consumer of
 * the trail (or of an event built from it) only knows the document and has to guess the envelope
 * from the folder or the file name.
 */
@JsonTypeName(AuditLogDetails.SIGNATURE)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = AuditLogDetails.DISCRIMINATOR + "=" + AuditLogDetails.SIGNATURE)
public class SignatureAudit extends AuditLogDetails {

    @Schema(description = "Id of the signature envelope")
    private UUID envelopeId;

    @Schema(description = "Title of the envelope")
    private String envelopeTitle;

    @Schema(description = "Document sent for signature")
    private UUID sourceDocumentId;

    @Schema(description = "Signed and sealed document, once the envelope is completed")
    private UUID signedDocumentId;

    @Schema(description = "Envelope status after the action")
    private String status;

    public static SignatureAudit of(SignatureEnvelope env) {
        return new SignatureAudit(env.getId(), env.getTitle(), env.getSourceDocId(), env.getSignedDocId(),
                env.getStatus() == null ? null : env.getStatus().name());
    }

    /** The completion entry: names the sealed document even if the envelope row does not carry it yet. */
    public static SignatureAudit completed(SignatureEnvelope env, UUID signedDocumentId) {
        return new SignatureAudit(env.getId(), env.getTitle(), env.getSourceDocId(), signedDocumentId, "COMPLETED");
    }
}
