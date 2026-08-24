package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openfilz.dms.enums.SignatureFieldType;

import java.util.Map;

/** A field in a template, attached to a role name instead of a recipient. */
public record SignatureTemplateField(
        @NotBlank @Size(max = 64) String role,
        @NotNull SignatureFieldType type,
        @NotNull @Min(0) Integer page,
        @NotNull Double x,
        @NotNull Double y,
        @NotNull Double w,
        @NotNull Double h,
        Boolean required,
        @Size(max = 255) String label,
        Map<String, Object> options
) {
    public SignatureFieldInput toInput() {
        return new SignatureFieldInput(type, page, x, y, w, h, required, label, options);
    }
}
