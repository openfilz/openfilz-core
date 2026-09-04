package org.openfilz.dms.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * Which items of a proposed reorganisation plan to apply.
 *
 * @param itemIds document ids of the plan items to move; null or empty = every applicable item
 */
public record ReorganizationApplyRequest(List<UUID> itemIds) {
}
