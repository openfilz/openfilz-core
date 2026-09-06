package org.openfilz.dms.service.workflow;

import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * In-app notification seam. Core has no notification system, so the default is a no-op; the
 * Enterprise Edition overrides it ({@code @Primary}) to push the existing bell / SSE
 * notifications. E-mails go through {@link WorkflowMailer}, not here.
 */
public interface WorkflowNotifier {

    /** A task was created (or reassigned): every candidate e-mail is listed; a ROLE task has none. */
    default Mono<Void> taskAssigned(WorkflowInstance instance, WorkflowTask task, List<String> candidateEmails) {
        return Mono.empty();
    }

    default Mono<Void> taskOverdue(WorkflowInstance instance, WorkflowTask task, List<String> candidateEmails) {
        return Mono.empty();
    }

    /** The instance reached an END status; {@code recipients} = initiator + NOTIFY action targets, de-duplicated. */
    default Mono<Void> completed(WorkflowInstance instance, List<String> recipients) {
        return Mono.empty();
    }

    default Mono<Void> cancelled(WorkflowInstance instance, String actorEmail, List<String> recipients) {
        return Mono.empty();
    }

    /** A NOTIFY action fired on a non-END status. */
    default Mono<Void> stateReached(WorkflowInstance instance, String stateLabel, List<String> recipients) {
        return Mono.empty();
    }
}
