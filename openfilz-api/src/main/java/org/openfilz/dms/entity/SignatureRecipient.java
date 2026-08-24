package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recipient on a {@link SignatureEnvelope}. {@code userId} is optional (known OpenFilz
 * user); every recipient authenticates through the tokenized link ({@code tokenHash} =
 * SHA-256 of the raw token that only ever lives in the email), optionally hardened by an OTP.
 *
 * <p>The legacy single-field placement columns ({@code field*}, {@code signatureImage},
 * {@code signatureTyped}) are kept for one release for backward compatibility; new code reads
 * and writes {@link SignatureField} rows.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("signature_recipient")
public class SignatureRecipient implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("envelope_id")
    private UUID envelopeId;

    @Column("user_id")
    private UUID userId;

    @Column("recipient_name")
    private String recipientName;

    @Column("recipient_email")
    private String recipientEmail;

    @Column("order_index")
    private int orderIndex;

    @Column("role")
    private SignatureRecipientRole role;

    @Column("auth_method")
    private SignatureAuthMethod authMethod;

    @Column("phone")
    private String phone;

    @Column("status")
    private SignatureRecipientStatus status;

    @Column("token_hash")
    private String tokenHash;

    @Column("token_revoked")
    private boolean tokenRevoked;

    @Column("otp_hash")
    private String otpHash;

    @Column("otp_expires_at")
    private OffsetDateTime otpExpiresAt;

    @Column("otp_attempts")
    private int otpAttempts;

    @Column("otp_verified_at")
    private OffsetDateTime otpVerifiedAt;

    @Column("locale")
    private String locale;

    @Column("reminder_count")
    private int reminderCount;

    /** Position in the create request — stable display order within the same {@code orderIndex}. */
    @Column("sort_order")
    private int sortOrder;

    // ── legacy single-field placement (read-only compatibility) ──
    @Column("field_page")
    private Integer fieldPage;
    @Column("field_x")
    private Double fieldX;
    @Column("field_y")
    private Double fieldY;
    @Column("field_w")
    private Double fieldW;
    @Column("field_h")
    private Double fieldH;
    @Column("signature_image")
    private String signatureImage;
    @Column("signature_typed")
    private String signatureTyped;

    @Column("viewed_at")
    private OffsetDateTime viewedAt;

    @Column("signed_at")
    private OffsetDateTime signedAt;

    @Column("signer_ip")
    private String signerIp;

    @Column("signer_user_agent")
    private String signerUserAgent;

    @Column("decline_reason")
    private String declineReason;

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isSigner() {
        return role == null || role == SignatureRecipientRole.SIGNER;
    }

    public boolean isActionable() {
        return isSigner() && (status == SignatureRecipientStatus.PENDING
                || status == SignatureRecipientStatus.VIEWED);
    }

    public boolean requiresOtp() {
        return authMethod != null && authMethod != SignatureAuthMethod.NONE;
    }
}
