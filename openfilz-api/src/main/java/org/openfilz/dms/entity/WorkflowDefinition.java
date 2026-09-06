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

/**
 * A named workflow: the state machine lives in {@code spec} (JSON of
 * {@link org.openfilz.dms.dto.workflow.WorkflowSpec}). Ids are pre-generated, hence
 * {@link Persistable} with an explicit {@code isNew} flag.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("workflow_definition")
public class WorkflowDefinition implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("name")
    private String name;

    @Column("description")
    private String description;

    @Column("active")
    private boolean active;

    @Column("spec")
    private Json spec;

    /** JSON array of folder UUIDs whose uploads start this workflow automatically. */
    @Column("trigger_folder_ids")
    private Json triggerFolderIds;

    @Column("version")
    private int version;

    @Column("created_by")
    private String createdBy;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
