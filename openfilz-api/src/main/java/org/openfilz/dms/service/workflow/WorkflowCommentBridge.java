package org.openfilz.dms.service.workflow;

import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import reactor.core.publisher.Mono;

/**
 * Decision-comment seam. The comment is always stored on the workflow history (source of
 * truth, shown in the timeline and to the next assignee); an edition with threaded document
 * comments may additionally echo it there. Core: no-op.
 */
public interface WorkflowCommentBridge {

    default Mono<Void> decisionCommented(WorkflowInstance instance, WorkflowTask task, String transitionLabel,
                                         String actorEmail, String comment) {
        return Mono.empty();
    }
}
