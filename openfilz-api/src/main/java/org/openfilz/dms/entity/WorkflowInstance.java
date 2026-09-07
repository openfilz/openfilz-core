package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.WorkflowInstanceStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** One document going through one definition. {@code spec} is the definition snapshot taken at start. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("workflow_instance")
public class WorkflowInstance implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("definition_id")
    private UUID definitionId;

    @Column("definition_name")
    private String definitionName;

    @Column("definition_version")
    private int definitionVersion;

    @Column("spec")
    private Json spec;

    @Column("document_id")
    private UUID documentId;

    @Column("document_name")
    private String documentName;

    @Column("status")
    private WorkflowInstanceStatus status;

    @Column("current_state_key")
    private String currentStateKey;

    @Column("current_state_label")
    private String currentStateLabel;

    @Column("started_by")
    private String startedBy;

    /** JSON object {@code stateKey → [emails]} for CHOSEN_AT_START statuses. */
    @Column("assignments")
    private Json assignments;

    @Column("locale")
    private String locale;

    @Column("started_at")
    private OffsetDateTime startedAt;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Column("completed_at")
    private OffsetDateTime completedAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
