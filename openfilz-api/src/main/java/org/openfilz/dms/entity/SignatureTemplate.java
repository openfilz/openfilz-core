package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Reusable envelope definition: named roles + their fields, owned by the user who created it. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("signature_template")
public class SignatureTemplate implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("owner_email")
    private String ownerEmail;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("source_doc_id")
    private UUID sourceDocId;

    /** JSON array of {@code SignatureTemplateRole}. */
    @Column("roles")
    private Json roles;

    /** JSON array of {@code SignatureTemplateField}. */
    @Column("fields")
    private Json fields;

    @Column("message")
    private String message;

    @Column("expires_in_days")
    private Integer expiresInDays;

    @Column("sequential")
    private boolean sequential;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
