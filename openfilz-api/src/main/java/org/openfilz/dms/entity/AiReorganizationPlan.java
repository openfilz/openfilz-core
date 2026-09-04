package org.openfilz.dms.entity;

import io.r2dbc.postgresql.codec.Json;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A validated document-reorganisation proposal awaiting (or past) the user's confirmation.
 * The {@code plan} column holds the serialized
 * {@link org.openfilz.dms.dto.response.ReorganizationPlanView}; {@code result} the per-item
 * outcome once applied.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_reorganization_plans")
public class AiReorganizationPlan {

    @Id
    @Column("id")
    private UUID id;

    @Column("created_by")
    private String createdBy;

    @Column("conversation_id")
    private UUID conversationId;

    @Column("root_folder_id")
    private UUID rootFolderId;

    @Column("status")
    private String status;

    @Column("plan")
    private Json plan;

    @Column("result")
    private Json result;

    @Column("created_at")
    private OffsetDateTime createdAt;

    @Column("applied_at")
    private OffsetDateTime appliedAt;

    /** PROPOSAL (chat / MCP, the default) or AUTO_FILE (smart filing). */
    @Column("origin")
    private String origin;

    /** The single document of an AUTO_FILE plan (its filing record). */
    @Column("document_id")
    private UUID documentId;

    /** Decision details of a filing: stage, confidence, reason, from. */
    @Column("details")
    private Json details;
}
