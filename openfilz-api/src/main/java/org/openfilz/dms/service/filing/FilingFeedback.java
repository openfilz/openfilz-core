package org.openfilz.dms.service.filing;

import org.openfilz.dms.dto.response.FilingOutcome;
import org.openfilz.dms.service.ai.ReorganizationPlanService;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The feedback seam of smart filing: what the user's corrections teach the neighbour vote.
 * <p>
 * The core neither records nor weighs corrections ({@link NoOpFilingFeedback}); an extension
 * registers a {@code @Primary} implementation that turns every undo into a labelled correction
 * and answers {@link #folderWeights} from them, so a folder the user keeps moving documents out
 * of loses its pull and one they keep moving documents into gains it. Both methods are called on
 * a filing worker thread and may block; a failure inside them never fails the filing.
 */
public interface FilingFeedback {

    /**
     * Multiplier per candidate folder (absent = 1.0) applied to the neighbours' similarity weight
     * in the vote. Values are clamped to [0.1, 3.0] by the vote.
     *
     * @param category  the document's tier-2 category, when known
     * @param folderIds the folders the neighbours live in — the candidates of the vote
     * @param userEmail the user filing (null in a no-auth deployment)
     */
    default Map<UUID, Double> folderWeights(String category, Collection<UUID> folderIds, String userEmail) {
        return Map.of();
    }

    /** A filing was undone by the user (toast Undo / chip Move back / job undo). */
    default void onUndone(FilingOutcome filing, ReorganizationPlanService.Caller caller) {
    }
}
