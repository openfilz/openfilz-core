package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.DashboardStatisticsResponse;
import reactor.core.publisher.Mono;

/**
 * Service for dashboard statistics and metrics
 */
public interface DashboardService {

    /**
     * Get aggregated dashboard statistics for the current user
     *
     * @return Dashboard statistics including file counts, storage usage, and breakdowns
     */
    Mono<DashboardStatisticsResponse> getDashboardStatistics();
}
