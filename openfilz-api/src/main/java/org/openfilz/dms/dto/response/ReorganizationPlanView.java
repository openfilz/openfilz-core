package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * A validated reorganisation plan: what would move where, and which moves are blocked and why.
 * This is what the chat proposal card renders, what an MCP agent gets back from
 * {@code proposeReorganizationPlan}, and what is persisted until the user confirms.
 *
 * @param id              plan id (null on a pure dry run that was not persisted)
 * @param status          PROPOSED, APPLIED, PARTIALLY_APPLIED, FAILED or DISCARDED
 * @param rootFolderId    folder the target paths are relative to (null = root level)
 * @param rootFolderPath  its absolute path, {@code "/"} for the root level
 * @param rationale       the model's explanation of the proposed hierarchy (may be null)
 * @param items           one entry per requested move, in request order
 * @param foldersToCreate target folder paths (relative to the root) that do not exist yet
 * @param applicable      number of items that can be applied as-is
 * @param blocked         number of items that cannot (see each item's {@code issue})
 * @param results         per-item outcome once applied (null before)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReorganizationPlanView(
        UUID id,
        String status,
        UUID rootFolderId,
        String rootFolderPath,
        String rationale,
        List<Item> items,
        List<String> foldersToCreate,
        int applicable,
        int blocked,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime appliedAt,
        List<ItemResult> results
) {

    /**
     * @param documentId   the document (or folder) to move — also the key used to select items on apply
     * @param name         its name
     * @param type         FILE or FOLDER
     * @param currentPath  absolute path of its current parent folder ({@code "/"} = root level)
     * @param targetPath   absolute path of the target folder
     * @param targetExists whether the target folder already exists (false = will be created)
     * @param applicable   whether the move can be applied
     * @param issue        why it cannot, when {@code applicable} is false
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(
            UUID documentId,
            String name,
            String type,
            String currentPath,
            String targetPath,
            boolean targetExists,
            boolean applicable,
            String issue
    ) {
    }

    /**
     * @param documentId the item
     * @param outcome    MOVED, SKIPPED (not selected / not applicable) or FAILED
     * @param detail     the failure reason, or null
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ItemResult(UUID documentId, String outcome, String detail) {
    }

    public ReorganizationPlanView withPersistence(UUID id, String status, String createdBy,
                                                  OffsetDateTime createdAt, OffsetDateTime appliedAt,
                                                  List<ItemResult> results) {
        return new ReorganizationPlanView(id, status, rootFolderId, rootFolderPath, rationale, items,
                foldersToCreate, applicable, blocked, createdBy, createdAt, appliedAt, results);
    }
}
