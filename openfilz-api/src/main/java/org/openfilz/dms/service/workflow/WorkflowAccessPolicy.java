package org.openfilz.dms.service.workflow;

import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.WorkflowInstance;
import reactor.core.publisher.Mono;

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
}
