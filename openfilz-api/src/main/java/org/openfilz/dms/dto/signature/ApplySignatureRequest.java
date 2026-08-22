package org.openfilz.dms.dto.signature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A recipient submitting their fields via the public signing endpoint.
 *
 * <p>New clients send {@code fields} (one entry per field id). The legacy single-field shape
 * — exactly one of {@code signatureImage} (base64 PNG) or {@code typedName} — is still
 * accepted and applied to every SIGNATURE / INITIALS field of the recipient.
 */
public record ApplySignatureRequest(
        String signatureImage,
        @Size(max = 255) String typedName,
        List<@Valid SignatureFieldValue> fields
) {
    public boolean isLegacy() {
        return fields == null || fields.isEmpty();
    }
}
