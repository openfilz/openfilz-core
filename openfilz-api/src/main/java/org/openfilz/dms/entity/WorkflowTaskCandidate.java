package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.UUID;

/** One candidate e-mail of a task (composite key, written through {@code DatabaseClient}). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table("workflow_task_candidate")
public class WorkflowTaskCandidate {

    @Column("task_id")
    private UUID taskId;

    @Column("email")
    private String email;
}
