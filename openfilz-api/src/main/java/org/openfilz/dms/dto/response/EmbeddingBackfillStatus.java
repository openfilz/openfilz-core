package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Progress of an embedding backfill job: what was queued and how each document ended
 * ({@code done} = chunks stored, {@code skipped} = no extractable text or no longer an active
 * file, {@code failed} = extraction or embedding error).
 */
public record EmbeddingBackfillStatus(
        UUID jobId,
        UUID folderId,
        boolean force,
        String status,
        int total,
        int done,
        int failed,
        int skipped,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt) {
    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
}
