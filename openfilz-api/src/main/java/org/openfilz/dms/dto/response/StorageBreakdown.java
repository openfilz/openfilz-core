package org.openfilz.dms.dto.response;

import java.util.List;

/**
 * Storage usage breakdown
 */
public record StorageBreakdown(
        Long totalStorageUsed,              // Total storage used in bytes
        Long totalStorageAvailable,         // The limit that applies to totalStorageUsed (null = unlimited): the instance
                                            // quota when the statistics cover the instance, the caller's when they are the caller's
        List<FileTypeStats> fileTypeBreakdown, // Breakdown by file type
        org.openfilz.dms.dto.response.quota.MyStorageQuota quota // The caller's own usage and effective limit (the dashboard ring)
) {
    public StorageBreakdown(Long totalStorageUsed, Long totalStorageAvailable, List<FileTypeStats> fileTypeBreakdown) {
        this(totalStorageUsed, totalStorageAvailable, fileTypeBreakdown, null);
    }
}
