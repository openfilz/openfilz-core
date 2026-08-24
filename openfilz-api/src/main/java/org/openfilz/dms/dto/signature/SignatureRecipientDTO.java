package org.openfilz.dms.dto.signature;

import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire view of a recipient for the initiator's tracking screens. Never exposes the token hash or OTP. */
public record SignatureRecipientDTO(
        UUID id,
        UUID userId,
        String name,
        String email,
        int orderIndex,
        SignatureRecipientRole role,
        SignatureAuthMethod authMethod,
        SignatureRecipientStatus status,
        OffsetDateTime viewedAt,
        OffsetDateTime signedAt,
        String declineReason,
        int reminderCount,
        List<SignatureFieldDTO> fields
) {
    public static SignatureRecipientDTO from(SignatureRecipient r, List<SignatureFieldDTO> fields) {
        return new SignatureRecipientDTO(r.getId(), r.getUserId(), r.getRecipientName(), r.getRecipientEmail(),
                r.getOrderIndex(), r.getRole() == null ? SignatureRecipientRole.SIGNER : r.getRole(),
                r.getAuthMethod() == null ? SignatureAuthMethod.NONE : r.getAuthMethod(),
                r.getStatus(), r.getViewedAt(), r.getSignedAt(), r.getDeclineReason(),
                r.getReminderCount(), fields);
    }
}
