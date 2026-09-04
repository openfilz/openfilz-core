package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** A smart-filing job: one per upload batch (or on-demand request), with the outcome of each document. */
public record AutoFileJobView(
        UUID jobId,
        String createdBy,
        String status,
        int total,
        int filed,
        int skipped,
        int failed,
        int pending,
        List<FilingOutcome> items,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt) {

    public static final String RUNNING = "RUNNING";
    public static final String DONE = "DONE";
    public static final String UNDONE = "UNDONE";
}
