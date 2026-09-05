package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Progress of an insight backfill job: what was queued and how each document ended. */
public record InsightBackfillStatus(
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
