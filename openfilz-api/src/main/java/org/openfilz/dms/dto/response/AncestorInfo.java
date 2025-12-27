package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * Represents an ancestor (parent folder) in the document hierarchy.
 * Used for building breadcrumb trails.
 *
 * @param id   The UUID of the ancestor folder.
 * @param name The name of the ancestor folder.
 * @param type The type of the document (always FOLDER for ancestors).
 */
public record AncestorInfo(UUID id, String name, String type) {}
