package org.openfilz.dms.dto.signature;

import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureRecipientStatus;

import java.util.List;

/**
 * What a signer sees when opening their tokenized link. Carries just enough to render the
 * signing experience — no other recipient's personal data, no internal ids beyond field ids.
 * Until the recipient's OTP step is passed, and once the envelope is closed (terminal, expired
 * or revoked link), only the status part is filled: {@code documentName}, {@code message},
 * {@code fields}, {@code otherFields} and the legacy projection are empty.
 *
 * @param myTurn       false while a sequential envelope is waiting for an earlier signer
 * @param otpRequired  the recipient must pass {@code /otp/request} + {@code /otp/verify} before signing
 * @param otpVerified  the OTP step was passed for this token (and has not aged past {@code otp.verified-for})
 * @param fields       this recipient's fields (with their values once filled)
 * @param otherFields  other recipients' already-filled fields as placements — position, type and
 *                     {@code filledAt}; never their {@code value} or {@code valueImage}
 */
public record PublicSignatureView(
        String envelopeTitle,
        String message,
        String initiatorEmail,
        String documentName,
        String recipientName,
        String recipientEmail,
        SignatureEnvelopeStatus envelopeStatus,
        SignatureRecipientStatus recipientStatus,
        boolean myTurn,
        SignatureAuthMethod authMethod,
        boolean otpRequired,
        boolean otpVerified,
        List<SignatureFieldDTO> fields,
        List<SignatureFieldDTO> otherFields,
        // ── legacy single-field projection (first SIGNATURE field) ──
        Integer fieldPage,
        Double fieldX,
        Double fieldY,
        Double fieldW,
        Double fieldH,
        String signatureImage,
        String signatureTyped
) {}
