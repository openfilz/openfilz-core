package org.openfilz.dms.entity;

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
 * Entity representing a user's favorite document (file or folder)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_favorites")
public class UserFavorite {

    @Id
    @Column("id")
    private Long id;

    @Column("user_id")
    private String userId;

    @Column("document_id")
    private UUID documentId;

    @Column("created_at")
    private OffsetDateTime createdAt;
}
