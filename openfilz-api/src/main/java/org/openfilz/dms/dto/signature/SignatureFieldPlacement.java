package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Legacy single signature-field placement (pre-1.3 API). Still accepted on
 * {@link SignatureRecipientInput#field()} and converted to one {@code SIGNATURE} field.
 */
public record SignatureFieldPlacement(
        @NotNull @Min(0) Integer page,
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double w,
        @NotNull Double h
) {
    public SignatureFieldInput toField() {
        return new SignatureFieldInput(org.openfilz.dms.enums.SignatureFieldType.SIGNATURE,
                page, x, y, w, h, true, null, null);
    }
}
