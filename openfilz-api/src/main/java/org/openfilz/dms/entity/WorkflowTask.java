package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.WorkflowTaskStatus;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** The pending decision of one status; candidates by e-mail live in {@code workflow_task_candidate}. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("workflow_task")
public class WorkflowTask implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("instance_id")
    private UUID instanceId;

    @Column("state_key")
    private String stateKey;

    @Column("state_label")
    private String stateLabel;

    /** Realm role whose holders may also complete the task (ROLE assignment). */
    @Column("candidate_role")
    private String candidateRole;

    @Column("status")
    private WorkflowTaskStatus status;

    @Column("due_at")
    private OffsetDateTime dueAt;

    @Column("reminded_at")
    private OffsetDateTime remindedAt;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("completed_at")
    private OffsetDateTime completedAt;

    @Column("completed_by")
    private String completedBy;

    @Column("transition_key")
    private String transitionKey;

    @Column("comment")
    private String comment;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
