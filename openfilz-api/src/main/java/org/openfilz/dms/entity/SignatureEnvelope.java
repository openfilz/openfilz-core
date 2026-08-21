package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * An e-Sign envelope: one source PDF dispatched to one or more recipients for signature.
 * Implements {@link Persistable} with an explicit {@code isNew} flag because ids are
 * pre-generated UUIDs (the R2DBC null-id heuristic would mis-route lifecycle UPDATEs as INSERTs).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("signature_envelope")
public class SignatureEnvelope implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("tenant_id")
    private UUID tenantId;

    /** Stable id of the initiator: the JWT {@code sub} when it is a UUID, else a name-UUID of the email. */
    @Column("initiator_id")
    private UUID initiatorId;

    @Column("initiator_email")
    private String initiatorEmail;

    @Column("title")
    private String title;

    @Column("message")
    private String message;

    @Column("source_doc_id")
    private UUID sourceDocId;

    @Column("signed_doc_id")
    private UUID signedDocId;

    @Column("signed_storage_path")
    private String signedStoragePath;

    @Column("original_sha256")
    private String originalSha256;

    @Column("signed_sha256")
    private String signedSha256;

    @Column("status")
    private SignatureEnvelopeStatus status;

    /** When true, recipients sign in {@code orderIndex} order; only the current order is notified. */
    @Column("sequential")
    private boolean sequential;

    /** Sequential mode: the order index currently allowed to sign. */
    @Column("current_order")
    private int currentOrder;

    @Column("template_id")
    private UUID templateId;

    /** Automatic reminders (EE scheduler): every N days while SENT. Null = none. */
    @Column("reminder_days")
    private Integer reminderDays;

    @Column("last_reminded_at")
    private OffsetDateTime lastRemindedAt;

    /** Locale used for recipient emails when the recipient has none (e.g. "fr"). */
    @Column("locale")
    private String locale;

    /** Which {@code SignatureSealer} produced the final document (set on completion). */
    @Column("seal_provider")
    private String sealProvider;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("sent_at")
    private OffsetDateTime sentAt;

    @Column("completed_at")
    private OffsetDateTime completedAt;

    @Column("cancelled_at")
    private OffsetDateTime cancelledAt;

    @Column("expires_at")
    private OffsetDateTime expiresAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
