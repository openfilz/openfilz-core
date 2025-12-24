package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * Represents the position of a document within its parent folder.
 * Used to calculate which page a document appears on in paginated views.
 *
 * @param documentId The UUID of the document.
 * @param parentId   The UUID of the parent folder, or null if at root level.
 * @param position   The 0-based index position of the document in the sorted folder contents.
 * @param totalItems The total number of items in the parent folder.
 */
public record DocumentPosition(UUID documentId, UUID parentId, long position, long totalItems) {}
