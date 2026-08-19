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

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("ai_chat_messages")
public class AiChatMessage {

    @Id
    @Column("id")
    private UUID id;

    @Column("conversation_id")
    private UUID conversationId;

    @Column("role")
    private String role; // USER, ASSISTANT, SYSTEM

    @Column("content")
    private String content;

    @Column("created_at")
    private OffsetDateTime createdAt;
}
