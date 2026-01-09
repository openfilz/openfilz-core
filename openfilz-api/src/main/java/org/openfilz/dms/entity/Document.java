// com/example/dms/entity/Document.java
package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.DocumentType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.openfilz.dms.entity.SqlTableMapping.DOCUMENT;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(DOCUMENT)
public class Document implements SqlColumnMapping, PhysicalDocument {

    @Id
    @Column(ID)
    private UUID id;

    @Column(NAME)
    private String name;

    @Column(TYPE)
    private DocumentType type;

    @Column(CONTENT_TYPE)
    private String contentType; // MIME type

    @Column(SIZE)
    private Long size; // in bytes

    @Column(PARENT_ID)
    private UUID parentId; // Null if root

    @Column(STORAGE_PATH)
    private String storagePath; // Path in FS or object key in S3

    @Column(METADATA)
    private Json metadata; // Stored as JSONB

    @Column(CREATED_AT)
    private OffsetDateTime createdAt;

    @Column(UPDATED_AT)
    private OffsetDateTime updatedAt;

    @Column(CREATED_BY)
    private String createdBy;

    @Column(UPDATED_BY)
    private String updatedBy;

    @Column(ACTIVE)
    private Boolean active; // When Soft delete is active : set to false to soft-delete a document

    @Transient
    private Boolean favorite; // Not stored in database, populated from JOIN

    @Transient
    private Boolean rootShare; // Not stored in database, populated from JOIN

    @Transient
    private Boolean sharedEdit; // Not stored in database, populated from JOIN
}