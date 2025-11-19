package org.openfilz.dms.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.openfilz.dms.entity.SqlColumnMapping.CREATED_AT;
import static org.openfilz.dms.entity.SqlTableMapping.USER_FAVORITES;
import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

/**
 * Entity representing a user's favorite document (file or folder)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table(USER_FAVORITES)
public class UserFavorite {

    @Column(EMAIL)
    private String email;

    @Column("doc_id")
    private UUID docId;

    @Column(CREATED_AT)
    private OffsetDateTime createdAt;
}
