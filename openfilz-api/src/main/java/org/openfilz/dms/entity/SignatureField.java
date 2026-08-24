package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.SignatureFieldType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A typed field placed for one recipient. Coordinates are 0..1 of the page media box, PDF origin bottom-left. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("signature_field")
public class SignatureField implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("envelope_id")
    private UUID envelopeId;

    @Column("recipient_id")
    private UUID recipientId;

    @Column("type")
    private SignatureFieldType type;

    @Column("page")
    private int page;

    @Column("x")
    private double x;
    @Column("y")
    private double y;
    @Column("w")
    private double w;
    @Column("h")
    private double h;

    @Column("required")
    private boolean required;

    @Column("label")
    private String label;

    /** Type-specific options, e.g. {@code {"choices":["A","B"],"group":"g1"}}. */
    @Column("options")
    private Json options;

    @Column("value")
    private String value;

    @Column("value_image")
    private String valueImage;

    @Column("filled_at")
    private OffsetDateTime filledAt;

    @Column("sort_order")
    private int sortOrder;

    @Override
    public boolean isNew() {
        return isNew;
    }

    public boolean isFilled() {
        return type != null && type.isImage()
                ? valueImage != null && !valueImage.isBlank()
                : value != null && !value.isBlank();
    }
}
