package org.openfilz.dms.dto.request;

import java.util.UUID;

/**
 * @param folderId restrict the backfill to this folder's subtree (null = the whole library)
 * @param force    re-enrich documents whose insight was produced by an older prompt version
 */
public record InsightBackfillRequest(UUID folderId, Boolean force) {
}
