package org.openfilz.dms.dto.request;

import java.util.UUID;

/**
 * @param folderId restrict the backfill to this folder's subtree (null = the whole library)
 * @param force    re-embed every file, not only those without a chunk in the vector store
 */
public record EmbeddingBackfillRequest(UUID folderId, Boolean force) {
}
