package org.openfilz.dms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiChatResponse {

    /**
     * The conversation ID (new or existing).
     */
    private UUID conversationId;

    /**
     * The AI assistant's response text.
     */
    private String content;

    /**
     * Type of SSE event: MESSAGE (content chunk), DONE (stream complete), ERROR.
     */
    private EventType type;

    public enum EventType {
        MESSAGE,
        DONE,
        ERROR
    }
}
