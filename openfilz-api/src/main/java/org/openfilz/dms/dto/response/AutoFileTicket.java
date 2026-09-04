package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * Attached to an upload response when smart filing was scheduled for it: the job to poll
 * ({@code GET /api/v1/ai/auto-file/{jobId}}) and its state at the time of the response.
 */
public record AutoFileTicket(UUID jobId, String status) {
}
