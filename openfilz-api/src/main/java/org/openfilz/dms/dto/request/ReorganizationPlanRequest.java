package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * A reorganisation proposal as produced by a model (the chat assistant or an external MCP agent):
 * which documents go into which folder of a new hierarchy. Everything is expressed relative to
 * {@code rootFolder}; the backend validates and enriches it into a
 * {@link org.openfilz.dms.dto.response.ReorganizationPlanView} before anything is shown or applied.
 *
 * @param rootFolder    id of the folder the target paths are relative to; null, blank or
 *                      {@code "root"} for the root level
 * @param moves         the documents to move and their target folder path
 * @param createFolders extra folder paths to create even when no move targets them (optional)
 * @param rationale     one or two sentences explaining the proposed hierarchy (optional, shown to
 *                      the user on the proposal card)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReorganizationPlanRequest(
        String rootFolder,
        List<Move> moves,
        List<String> createFolders,
        String rationale
) {

    /**
     * @param document id of the document or folder to move (its exact, unique name is accepted too)
     * @param target   folder path relative to the plan's root folder, e.g. {@code "Finance/Invoices/2026"};
     *                 empty or {@code "/"} means the root folder itself. Missing folders are created.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Move(String document, String target) {
    }
}
