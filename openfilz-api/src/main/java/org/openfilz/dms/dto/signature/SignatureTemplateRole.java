package org.openfilz.dms.dto.signature;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureRecipientRole;

/** A named role in a template (e.g. "Client", "Sales rep"), bound to a concrete person at instantiation. */
public record SignatureTemplateRole(
        @NotBlank @Size(max = 64) String name,
        @Min(0) Integer orderIndex,
        SignatureRecipientRole role,
        SignatureAuthMethod authMethod
) {}
