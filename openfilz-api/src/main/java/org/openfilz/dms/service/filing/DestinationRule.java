package org.openfilz.dms.service.filing;

import org.openfilz.dms.dto.response.DocumentInsightView;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.ai.ReorganizationPlanService;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * A filing rule consulted before the neighbour vote: the policy stage of smart filing.
 * <p>
 * Every {@code DestinationRule} bean is asked, in {@link #order()} order, once the document's
 * insight and text head are known. A rule may do three things, alone or together: <em>re-scope</em>
 * the filing ({@link Decision#scopeOverride()} — the Inbox convention widens the scope from the
 * folder the document was dropped in to the whole library), <em>exclude</em> folders from ever
 * being chosen by the vote, the rule-by-kind or the model
 * ({@link Decision#excludedDestinations()}), or <em>decide</em> the destination outright
 * ({@link Decision#targetPath()}) — the first rule that names a target ends the stage, and the
 * document is filed with stage {@code POLICY} (or left where it is with the reason when the rule
 * runs {@link Decision#dryRun() dry}). An empty answer means the rule has nothing to say.
 * <p>
 * The core ships the Inbox rule only; an extension registers its own beans (organisation-level
 * policies, team routing). Rules run on a filing worker thread and may block.
 */
public interface DestinationRule {

    /** Lower runs first. */
    default int order() {
        return 0;
    }

    /** Blocking (worker thread). Empty = the rule has nothing to say. */
    Optional<Decision> decide(FilingContext context);

    /**
     * What a rule sees.
     *
     * @param document  the document being filed (its {@code parentId} is where it lies now)
     * @param insight   the tier-2 insight row when one exists (category, language, entities…), else null
     * @param scopeRoot the filing scope so far (null = the whole library), after the rules before this one
     * @param scopePath the readable path of that scope ("/" for the library)
     * @param caller    who files: the identity the move is audited under
     * @param textHead  the beginning of the document's text; empty when nothing could be extracted
     * @param metadata  the document's user metadata (JSONB), never null
     */
    record FilingContext(Document document, DocumentInsightView insight, UUID scopeRoot, String scopePath,
                         ReorganizationPlanService.Caller caller, String textHead, Map<String, Object> metadata) {
    }

    /**
     * @param scopeOverride        when true, {@code scopeRoot} replaces the filing scope (null = whole library) and the pipeline continues
     * @param scopeRoot            the new scope root when {@code scopeOverride} is set
     * @param targetPath           folder path relative to the (possibly overridden) scope root; null = no destination decided
     * @param allowCreateFolders   whether a missing {@code targetPath} may be created; otherwise the document is SKIPPED
     * @param dryRun               record what the rule would do, without moving anything
     * @param ruleId               a short stable identifier of the rule, kept in the filing record ({@code details.ruleId})
     * @param reason               one sentence for the user
     * @param excludedDestinations folders that must never be chosen by the vote/model (e.g. an Inbox); their subtrees included
     */
    record Decision(boolean scopeOverride, UUID scopeRoot, String targetPath, boolean allowCreateFolders,
                    boolean dryRun, String ruleId, String reason, Set<UUID> excludedDestinations) {

        public Decision {
            excludedDestinations = excludedDestinations == null ? Set.of() : Set.copyOf(excludedDestinations);
        }

        /** A decision that only re-scopes the filing and/or excludes folders; the pipeline goes on. */
        public static Decision scope(UUID scopeRoot, String ruleId, String reason, Set<UUID> excludedDestinations) {
            return new Decision(true, scopeRoot, null, false, false, ruleId, reason, excludedDestinations);
        }

        /** A decision that only excludes folders from the destinations; scope and pipeline unchanged. */
        public static Decision exclude(String ruleId, String reason, Set<UUID> excludedDestinations) {
            return new Decision(false, null, null, false, false, ruleId, reason, excludedDestinations);
        }

        /** A decision that names the destination (relative to the current scope root). */
        public static Decision target(String targetPath, boolean allowCreateFolders, boolean dryRun, String ruleId, String reason) {
            return new Decision(false, null, targetPath, allowCreateFolders, dryRun, ruleId, reason, Set.of());
        }
    }
}
