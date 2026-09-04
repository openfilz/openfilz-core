package org.openfilz.dms.dto.response;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * How smart filing ended for one document.
 *
 * @param status     FILED (moved), SKIPPED (stayed where it was, see reason), FAILED, UNDONE (moved back)
 * @param stage      what decided the destination: NEIGHBOURS (vector vote), MODEL, or NONE
 * @param confidence the vote share or the model's confidence (0..1), null when nothing was decided
 * @param planId     the AUTO_FILE plan record behind the move (history, undo)
 */
public record FilingOutcome(
        UUID documentId,
        String name,
        String status,
        UUID fromFolderId,
        String fromPath,
        UUID toFolderId,
        String toPath,
        String stage,
        Double confidence,
        String reason,
        UUID planId,
        OffsetDateTime decidedAt) {

    public static final String FILED = "FILED";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
    public static final String UNDONE = "UNDONE";
    public static final String PENDING = "PENDING";

    public static final String STAGE_NEIGHBOURS = "NEIGHBOURS";
    public static final String STAGE_MODEL = "MODEL";
    public static final String STAGE_NONE = "NONE";

    public FilingOutcome withStatus(String newStatus, String newReason) {
        return new FilingOutcome(documentId, name, newStatus, fromFolderId, fromPath, toFolderId, toPath, stage,
                confidence, newReason, planId, OffsetDateTime.now());
    }
}
