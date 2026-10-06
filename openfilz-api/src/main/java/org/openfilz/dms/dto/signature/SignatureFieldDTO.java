package org.openfilz.dms.dto.signature;

import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.utils.SignatureJson;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Wire view of a placed field. In the public (signer) view, values are only included for the
 * signer's own fields; other recipients' fields come through {@link #placement} — position and
 * fill status, never what they wrote or drew.
 */
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

    /** Position + fill status only — no typed value, no image. For another recipient's fields. */
    public static SignatureFieldDTO placement(SignatureField f) {
        return new SignatureFieldDTO(f.getId(), f.getRecipientId(), f.getType(), f.getPage(),
                f.getX(), f.getY(), f.getW(), f.getH(), f.isRequired(), f.getLabel(),
                SignatureJson.toMap(f.getOptions()),
                null, null, f.getFilledAt());
    }
}
