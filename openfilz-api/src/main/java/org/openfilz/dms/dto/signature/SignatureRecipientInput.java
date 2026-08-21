package org.openfilz.dms.dto.signature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureRecipientRole;

import java.util.List;
import java.util.UUID;

/**
 * One recipient on a {@link CreateSignatureEnvelopeRequest}.
 *
 * @param userId     optional — set when the recipient is a known OpenFilz user (in-app notification)
 * @param orderIndex signing order (sequential envelopes); recipients with the same index sign in parallel
 * @param role       SIGNER (default) or CC
 * @param authMethod NONE (default), EMAIL_OTP or SMS_OTP (needs {@code phone})
 * @param fields     the fields this recipient must fill (at least one SIGNATURE for a SIGNER)
 * @param field      legacy single placement — converted to one SIGNATURE field when {@code fields} is empty
 */
public record SignatureRecipientInput(
        UUID userId,
        @Size(max = 255) String name,
        @NotNull @Email @Size(max = 255) String email,
        @Min(0) Integer orderIndex,
        SignatureRecipientRole role,
        SignatureAuthMethod authMethod,
        @Size(max = 32) String phone,
        @Size(max = 8) String locale,
        @Valid List<SignatureFieldInput> fields,
        @Valid SignatureFieldPlacement field
) {
    public List<SignatureFieldInput> effectiveFields() {
        if (fields != null && !fields.isEmpty()) return fields;
        if (field != null) return List.of(field.toField());
        return List.of();
    }

    public SignatureRecipientRole effectiveRole() {
        return role == null ? SignatureRecipientRole.SIGNER : role;
    }

    public SignatureAuthMethod effectiveAuthMethod() {
        return authMethod == null ? SignatureAuthMethod.NONE : authMethod;
    }

    public int effectiveOrderIndex() {
        return orderIndex == null ? 0 : orderIndex;
    }
}
