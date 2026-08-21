package org.openfilz.dms.dto.signature;

import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.enums.SignatureEventType;

import java.time.OffsetDateTime;

public record SignatureEventDTO(
        SignatureEventType type,
        String actor,
        String docSha256,
        String signerIp,
        String details,
        OffsetDateTime createdAt
) {
    public static SignatureEventDTO from(SignatureEvent e) {
        return new SignatureEventDTO(e.getEventType(), e.getActor(), e.getDocSha256(), e.getSignerIp(),
                e.getDetails(), e.getCreatedAt());
    }
}
