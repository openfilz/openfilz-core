package org.openfilz.dms.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of applying a reorganisation plan.
 *
 * @param planId            the plan
 * @param status            the plan's new status: APPLIED, PARTIALLY_APPLIED or FAILED
 * @param moved             items moved
 * @param failed            items that could not be moved (see the plan's per-item results)
 * @param skipped           items not selected or not applicable
 * @param createdFolders    absolute paths of the folders created
 * @param modifiedFolderIds folders whose direct content changed ({@code "root"} for the root level),
 *                          for the file explorer to refresh
 * @param plan              the plan after the apply, with per-item results
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReorganizationApplyResult(
        UUID planId,
        String status,
        int moved,
        int failed,
        int skipped,
        List<String> createdFolders,
        List<String> modifiedFolderIds,
        ReorganizationPlanView plan
) {
}
