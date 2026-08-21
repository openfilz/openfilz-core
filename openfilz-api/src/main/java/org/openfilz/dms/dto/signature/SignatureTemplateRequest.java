package org.openfilz.dms.dto.signature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Create / update a reusable template. Every field's {@code role} must match one of {@code roles}. */
public record SignatureTemplateRequest(
        @NotBlank @Size(max = 255) String name,
        @Size(max = 2000) String description,
        UUID sourceDocId,
        @NotEmpty @Valid List<SignatureTemplateRole> roles,
        @NotEmpty @Valid List<SignatureTemplateField> fields,
        @Size(max = 2000) String message,
        @Min(1) @Max(365) Integer expiresInDays,
        Boolean sequential
) {}
