package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.WorkflowInstanceScope;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.WorkflowInstance;
import reactor.core.publisher.Mono;

import java.util.UUID;

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
}
