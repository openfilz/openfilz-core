package org.openfilz.dms.service.ai;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Side effects a per-request tool object accumulated during one chat turn, reported back to the
 * chat pipeline once the model has answered.
 * <p>
 * {@code DocumentAiTools} is wired into the chat directly and exposes the same information through
 * its own getters; the tool objects that reach the chat through an
 * {@link org.openfilz.dms.service.mcp.McpToolContributor} (PDF, e-Sign, reorganisation) are handed
 * to the pipeline as plain {@code Object}s, so this is the seam through which they report:
 * <ul>
 *   <li>{@link #modifiedFolders()} — folders whose direct content changed, so the file explorer
 *       refreshes, and so the failover logic knows a mutation already committed this turn;</li>
 *   <li>{@link #performedActions()} — the human-readable log used when the model fails after a
 *       mutation and the pipeline has to confirm what was done on its own;</li>
 *   <li>{@link #proposedPlanIds()} — reorganisation plans proposed this turn; the pipeline appends a
 *       {@code [[reorg-plan:id]]} marker to the answer so the frontend renders the proposal card.</li>
 * </ul>
 * All methods default to "nothing", so a tool object may implement only what applies.
 */
public interface AiToolTurnEffects {

    /** Sentinel for the root level, identical to {@code DocumentAiTools.ROOT_FOLDER_ID}. */
    String ROOT_FOLDER_ID = "root";

    default Set<String> modifiedFolders() {
        return Set.of();
    }

    default List<String> performedActions() {
        return List.of();
    }

    default List<UUID> proposedPlanIds() {
        return List.of();
    }

    /** The chat conversation this turn belongs to (never called from the MCP server). */
    default void bindConversation(UUID conversationId) {
    }

    static String folderKey(UUID folderId) {
        return folderId != null ? folderId.toString() : ROOT_FOLDER_ID;
    }
}
