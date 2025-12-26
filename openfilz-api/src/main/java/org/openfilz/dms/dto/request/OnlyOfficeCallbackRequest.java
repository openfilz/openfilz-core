package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Request body from OnlyOffice DocumentServer callback.
 * Sent when document state changes (editing, saved, closed, etc.).
 *
 * @see <a href="https://api.onlyoffice.com/editors/callback">OnlyOffice Callback API</a>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "OnlyOffice DocumentServer callback request")
public record OnlyOfficeCallbackRequest(
        @Schema(description = "Document status: 0=no doc, 1=editing, 2=ready to save, 3=error, 4=closed, 6=force save, 7=force save error")
        int status,

        @Schema(description = "Document key used to identify the document")
        String key,

        @Schema(description = "URL to download the modified document (only when status=2 or 6)")
        String url,

        @Schema(description = "List of user IDs currently editing the document")
        List<String> users,

        @Schema(description = "URL to download document changes history")
        String changesurl,

        @Schema(description = "Type of force save: 0=command, 1=timer, 2=user disconnect")
        Integer forcesavetype,

        @Schema(description = "List of actions performed on the document")
        List<Action> actions,

        @Schema(description = "Custom user data passed when opening the editor")
        String userdata,

        @Schema(description = "JWT token for callback validation")
        String token
) {
    /**
     * Action performed by a user on the document.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Action(
            @Schema(description = "Action type: 0=disconnect, 1=connect, 2=force disconnect")
            int type,

            @Schema(description = "User ID who performed the action")
            String userid
    ) {}

    /**
     * Status codes from OnlyOffice callback.
     */
    public static class Status {
        /** No document with the specified key */
        public static final int NO_DOCUMENT = 0;
        /** Document is being edited */
        public static final int EDITING = 1;
        /** Document is ready for saving (download URL available) */
        public static final int READY_FOR_SAVE = 2;
        /** Error saving document */
        public static final int SAVE_ERROR = 3;
        /** Document closed without changes */
        public static final int CLOSED = 4;
        /** Force save triggered (autosave) */
        public static final int FORCE_SAVE = 6;
        /** Error during force save */
        public static final int FORCE_SAVE_ERROR = 7;
    }

    /**
     * Check if the callback indicates the document should be saved.
     */
    public boolean shouldSave() {
        return status == Status.READY_FOR_SAVE || status == Status.FORCE_SAVE;
    }

    /**
     * Check if the callback indicates an error.
     */
    public boolean isError() {
        return status == Status.SAVE_ERROR || status == Status.FORCE_SAVE_ERROR;
    }
}
