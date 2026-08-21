package org.openfilz.dms.dto.signature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Initiator's request to send a PDF document for signature.
 *
 * @param sourceDocId   the PDF document (a core {@code documents} row) to sign
 * @param title         envelope title shown to recipients
 * @param message       optional personal message included in the invitation email
 * @param recipients    one or more recipients, each with their placed fields
 * @param expiresInDays envelope TTL in days (1..365, default {@code openfilz.signature.default-expiry-days})
 * @param sequential    when true recipients sign in {@code orderIndex} order
 * @param reminderDays  automatic reminder cadence in days (EE scheduler); null = none
 * @param locale        email locale fallback (e.g. "fr"); defaults to "en"
 * @param send          false keeps the envelope as a DRAFT (no email) — default true
 * @param templateId    informative link to the template the envelope was built from
 */
public record CreateSignatureEnvelopeRequest(
        @NotNull UUID sourceDocId,
        @NotBlank @Size(max = 255) String title,
        @Size(max = 2000) String message,
        @NotEmpty @Valid List<SignatureRecipientInput> recipients,
        @Min(1) @Max(365) Integer expiresInDays,
        Boolean sequential,
        @Min(1) @Max(90) Integer reminderDays,
        @Size(max = 8) String locale,
        Boolean send,
        UUID templateId
) {
    public boolean shouldSend() {
        return send == null || send;
    }

    public boolean isSequential() {
        return Boolean.TRUE.equals(sequential);
    }
}
