package org.openfilz.dms.dto.signature;

import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire view of an envelope. {@code signedDocId} is populated once COMPLETED. */
public record SignatureEnvelopeDTO(
        UUID id,
        String title,
        String message,
        UUID sourceDocId,
        UUID signedDocId,
        SignatureEnvelopeStatus status,
        String initiatorEmail,
        boolean sequential,
        int currentOrder,
        UUID templateId,
        Integer reminderDays,
        String sealProvider,
        OffsetDateTime createdAt,
        OffsetDateTime sentAt,
        OffsetDateTime completedAt,
        OffsetDateTime expiresAt,
        List<SignatureRecipientDTO> recipients
) {
    public static SignatureEnvelopeDTO from(SignatureEnvelope e, List<SignatureRecipientDTO> recipients) {
        return new SignatureEnvelopeDTO(e.getId(), e.getTitle(), e.getMessage(), e.getSourceDocId(),
                e.getSignedDocId(), e.getStatus(), e.getInitiatorEmail(), e.isSequential(), e.getCurrentOrder(),
                e.getTemplateId(), e.getReminderDays(), e.getSealProvider(), e.getCreatedAt(), e.getSentAt(),
                e.getCompletedAt(), e.getExpiresAt(), recipients);
    }
}
