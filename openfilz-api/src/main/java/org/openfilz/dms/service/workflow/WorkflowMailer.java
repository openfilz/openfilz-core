package org.openfilz.dms.service.workflow;

import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;

/**
 * E-mail seam (fire-and-forget). Chosen at runtime in {@code WorkflowConfig}: SMTP when
 * {@code spring.mail.host} is set, otherwise a logger. Tests replace it with a capturing bean.
 */
public interface WorkflowMailer {

    /** "The document X waits for your decision" — {@code previousComment} may be null. */
    void sendTaskAssigned(WorkflowInstance instance, WorkflowTask task, String toEmail, String link, String previousComment);

    void sendTaskOverdue(WorkflowInstance instance, WorkflowTask task, String toEmail, String link);

    void sendCompleted(WorkflowInstance instance, String toEmail, String link);

    void sendCancelled(WorkflowInstance instance, String toEmail, String actorEmail, String comment, String link);

    /** A NOTIFY action on a non-END status. */
    void sendStateReached(WorkflowInstance instance, String stateLabel, String toEmail, String link);
}
