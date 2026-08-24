package org.openfilz.dms.dto.signature;

import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.utils.SignatureJson;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Wire view of a placed field. {@code valueImage} is only included for the signer's own fields (public view). */
public record SignatureFieldDTO(
        UUID id,
        UUID recipientId,
        SignatureFieldType type,
        int page,
        double x,
        double y,
        double w,
        double h,
        boolean required,
        String label,
        Map<String, Object> options,
        String value,
        String valueImage,
        OffsetDateTime filledAt
) {
    public static SignatureFieldDTO from(SignatureField f, boolean includeImage) {
        return new SignatureFieldDTO(f.getId(), f.getRecipientId(), f.getType(), f.getPage(),
                f.getX(), f.getY(), f.getW(), f.getH(), f.isRequired(), f.getLabel(),
                SignatureJson.toMap(f.getOptions()),
                f.getValue(), includeImage ? f.getValueImage() : null, f.getFilledAt());
    }
}
