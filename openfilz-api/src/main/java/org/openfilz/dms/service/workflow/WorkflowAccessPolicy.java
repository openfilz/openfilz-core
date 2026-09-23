package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.WorkflowInstanceScope;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.WorkflowDefinition;
import org.openfilz.dms.entity.WorkflowInstance;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Document-access seam. Core has no per-document permissions: anyone may start a workflow on
 * an active file, everyone sees every instance, and only the initiator manages one. The
 * Enterprise Edition answers from its ownership / share model instead.
 */
public interface WorkflowAccessPolicy {

    Mono<Boolean> canStart(Document document, String userEmail);

    default Mono<Boolean> canView(WorkflowInstance instance, String userEmail) {
        return Mono.just(true);
    }

    /** Cancel / reassign. */
    default Mono<Boolean> canManage(WorkflowInstance instance, String userEmail) {
        return Mono.just(instance.getStartedBy() != null && instance.getStartedBy().equalsIgnoreCase(userEmail));
    }

    /**
     * The SQL counterpart of {@link #canView}: what the monitor's listing, its total and its
     * summary counters are restricted to. Filtering there rather than over the fetched page keeps
     * the three consistent — an implementation that overrides {@code canView} must override this
     * too, or the page shrinks while the counters keep describing everyone's instances.
     */
    default Mono<WorkflowInstanceScope> visibleInstances(String userEmail) {
        return Mono.just(WorkflowInstanceScope.ALL);
    }

    /**
     * May this user point a workflow definition at that folder — as a hot folder, or as the
     * destination of a MOVE_TO_FOLDER action? Both end up writing into it, so the Enterprise
     * Edition answers on write access; core has no per-document permissions and allows any folder.
     * Checked when a definition is saved, so the API does not depend on the designer's folder picker.
     */
    default Mono<Boolean> canUseFolder(UUID folderId, String userEmail) {
        return Mono.just(true);
    }

    /**
     * May this user change that definition — edit it, activate/deactivate it, delete it?
     * <p>
     * Only asked about a definition {@link #visibleDefinitions} already lets the user see: seeing a
     * workflow is enough to start it, the <em>changes</em> need an owner, or one designer silently
     * rewrites another's workflow. Core has no notion of ownership and allows everyone; the
     * Enterprise Edition answers "its author, or an admin".
     */
    default Mono<Boolean> canEditDefinition(WorkflowDefinition definition, String userEmail, List<String> roles) {
        return Mono.just(true);
    }

    /**
     * Which definitions this user sees: the designer's catalogue, the start dialog, and every
     * definition id the API is handed (read, change, start) — one it cannot see answers 404, as if
     * it did not exist. Core has no notion of whom a definition is meant for, so everyone sees the
     * whole catalogue; an edition that scopes it answers with the test to apply.
     * <p>
     * Resolved once per request and then applied to each definition, so a listing costs one lookup
     * whatever the size of the catalogue. Hot folders are not asked: an upload into one starts its
     * workflow whoever uploads, as its designer configured it — the folder's own permissions decide
     * who may upload there ({@link #canUseFolder}).
     */
    default Mono<Predicate<WorkflowDefinition>> visibleDefinitions(String userEmail, List<String> roles) {
        return Mono.just(definition -> true);
    }
}
