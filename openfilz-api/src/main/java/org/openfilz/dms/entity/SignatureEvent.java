package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.SignatureEventType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only audit trail entry for an envelope. Rendered into the Certificate of Completion. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("signature_event")
public class SignatureEvent implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("envelope_id")
    private UUID envelopeId;

    @Column("event_type")
    private SignatureEventType eventType;

    @Column("actor")
    private String actor;

    @Column("doc_sha256")
    private String docSha256;

    @Column("signer_ip")
    private String signerIp;

    @Column("details")
    private String details;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
