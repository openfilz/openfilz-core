package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A recipient's answer for one field: {@code value} for text-like types, {@code valueImage} (base64 PNG) for image types. */
public record SignatureFieldValue(
        @NotNull UUID fieldId,
        @Size(max = 4000) String value,
        String valueImage
) {}
