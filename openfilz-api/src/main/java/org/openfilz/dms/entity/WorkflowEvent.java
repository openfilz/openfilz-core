package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.openfilz.dms.enums.WorkflowEventType;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Transient;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Append-only history entry of an instance (the timeline). */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("workflow_event")
public class WorkflowEvent implements Persistable<UUID> {

    @Id
    @Column("id")
    private UUID id;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Column("instance_id")
    private UUID instanceId;

    @Column("event_type")
    private WorkflowEventType eventType;

    @Column("from_state")
    private String fromState;

    @Column("to_state")
    private String toState;

    @Column("transition_key")
    private String transitionKey;

    @Column("actor")
    private String actor;

    @Column("comment")
    private String comment;

    @Column("details")
    private Json details;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Override
    public boolean isNew() {
        return isNew;
    }
}
