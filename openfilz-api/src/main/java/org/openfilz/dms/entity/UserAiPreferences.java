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

/** A user's AI preferences that need no BYOK key: the smart-filing switch and its new-folder option. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("user_ai_preferences")
public class UserAiPreferences implements Persistable<String> {

    @Id
    @Column("user_email")
    private String userEmail;

    @Column("auto_file")
    private boolean autoFile;

    @Column("auto_file_new_folders")
    private boolean autoFileNewFolders;

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
