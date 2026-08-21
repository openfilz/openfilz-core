package org.openfilz.dms.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
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

    /**
     * IDs of folders whose direct content was modified by tool calls during this turn
     * ("root" for the root level). Only set on the DONE event when at least one folder
     * changed — lets the frontend refresh the file explorer when the displayed folder
     * is affected.
     */
    private List<String> modifiedFolderIds;

    public enum EventType {
        MESSAGE,
        DONE,
        ERROR
    }
}
