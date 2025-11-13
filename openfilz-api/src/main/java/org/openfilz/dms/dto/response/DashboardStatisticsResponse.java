package org.openfilz.dms.dto.response;

import java.util.List;

/**
 * Dashboard statistics response containing aggregated metrics
 */
public record DashboardStatisticsResponse(
        Long totalFiles,                    // Total number of files
        Long totalFolders,                  // Total number of folders
        StorageBreakdown storage,           // Storage usage breakdown
        List<FileTypeStats> fileTypeCounts  // File counts by type
) {
}
