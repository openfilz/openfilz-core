package org.openfilz.dms.dto.signature;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SignatureTemplateDTO(
        UUID id,
        String ownerEmail,
        String name,
        String description,
        UUID sourceDocId,
        List<SignatureTemplateRole> roles,
        List<SignatureTemplateField> fields,
        String message,
        Integer expiresInDays,
        boolean sequential,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
