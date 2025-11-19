package org.openfilz.dms.dto.response;

import java.util.List;

/**
 * Storage usage breakdown
 */
public record StorageBreakdown(
        Long totalStorageUsed,              // Total storage used in bytes
        Long totalStorageAvailable,         // Total storage available (quota, if applicable)
        List<FileTypeStats> fileTypeBreakdown  // Breakdown by file type
) {
}
