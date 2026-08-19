package org.openfilz.dms.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatRequest {

    /**
     * The user's message/question.
     */
    @NotBlank(message = "Message must not be blank")
    private String message;

    /**
     * Optional conversation ID for multi-turn conversations.
     * If null, a new conversation is created.
     */
    private UUID conversationId;
}
