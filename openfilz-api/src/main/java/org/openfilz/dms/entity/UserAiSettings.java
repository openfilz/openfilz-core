package org.openfilz.dms.entity;

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

/**
 * A user's personal chat-LLM override (BYOK). Keyed by email, like {@link UserFavorite}.
 * <p>
 * Implements {@link Persistable} because the primary key is a natural key: Spring Data R2DBC
 * would otherwise treat every save of a new row (non-null id) as an UPDATE. The service sets
 * {@code isNew} explicitly after the exists-check.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_ai_settings")
public class UserAiSettings implements Persistable<String> {

    @Id
    @Column("user_email")
    private String userEmail;

    @Column("provider")
    private String provider;

    @Column("model")
    private String model;

    @Column("base_url")
    private String baseUrl;

    /** AES-256-GCM encrypted API key: base64(iv || ciphertext+tag). Never exposed via the API. */
    @Column("api_key_encrypted")
    private String apiKeyEncrypted;

    @Column("updated_at")
    private OffsetDateTime updatedAt;

    @Transient
    @Builder.Default
    private boolean isNew = false;

    @Override
    public String getId() {
        return userEmail;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }
}
