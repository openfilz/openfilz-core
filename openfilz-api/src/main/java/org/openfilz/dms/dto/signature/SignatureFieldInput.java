package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.openfilz.dms.enums.SignatureFieldType;

import java.util.Map;

/**
 * A field the initiator places for one recipient. Coordinates are normalized to the page
 * media box (0..1, PDF origin bottom-left); {@code page} is 0-based.
 *
 * <p>Fields are rendered and filled in array order.
 *
 * @param options type-specific options: {@code choices} (RADIO / SELECT, list of strings),
 *                {@code group} (RADIO group name), {@code format} (DATE_SIGNED pattern),
 *                {@code placeholder}, {@code defaultValue}
 */
public record SignatureFieldInput(
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
    public boolean isRequired() {
        return required == null || required;
    }
}
