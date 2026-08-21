package org.openfilz.dms.dto.signature;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Build an envelope from a template: bind every template role to a person.
 *
 * @param sourceDocId overrides the template's default document (required when the template has none)
 */
public record InstantiateTemplateRequest(
        UUID sourceDocId,
        @Size(max = 255) String title,
        @Size(max = 2000) String message,
        @NotEmpty @Valid List<RoleBinding> recipients,
        @Min(1) @Max(365) Integer expiresInDays,
        @Min(1) @Max(90) Integer reminderDays,
        @Size(max = 8) String locale,
        Boolean send
) {
    public record RoleBinding(
            @NotBlank @Size(max = 64) String role,
            UUID userId,
            @Size(max = 255) String name,
            @NotNull @Email @Size(max = 255) String email,
            @Size(max = 32) String phone,
            @Size(max = 8) String locale
    ) {}
}
