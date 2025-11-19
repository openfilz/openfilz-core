package org.openfilz.dms.dto.response;

/**
 * Statistics for a specific file type category
 */
public record FileTypeStats(
        String type,        // File type category (e.g., "documents", "images", "videos")
        Long count,         // Number of files of this type
        Long totalSize      // Total size in bytes for this file type
) {
}
