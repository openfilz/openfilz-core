package org.openfilz.dms.service.workflow.impl;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.service.workflow.WorkflowMailer;

/** Used when no SMTP host is configured: logs what would have been sent (dev / evaluation only). */
@Slf4j
public class LoggingWorkflowMailer implements WorkflowMailer {

    @Override
    public void sendTaskAssigned(WorkflowInstance instance, WorkflowTask task, String toEmail, String link, String previousComment) {
        log.warn("[workflows][no-smtp] task '{}' on '{}' ({}) assigned to {} — link: {}", task.getStateLabel(),
                instance.getDocumentName(), instance.getDefinitionName(), toEmail, link);
    }

    @Override
    public void sendTaskOverdue(WorkflowInstance instance, WorkflowTask task, String toEmail, String link) {
        log.warn("[workflows][no-smtp] task '{}' on '{}' overdue — reminder to {} — link: {}", task.getStateLabel(),
                instance.getDocumentName(), toEmail, link);
    }

    @Override
    public void sendCompleted(WorkflowInstance instance, String toEmail, String link) {
        log.warn("[workflows][no-smtp] workflow '{}' on '{}' completed ({}) — would notify {}", instance.getDefinitionName(),
                instance.getDocumentName(), instance.getCurrentStateLabel(), toEmail);
    }

    @Override
    public void sendCancelled(WorkflowInstance instance, String toEmail, String actorEmail, String comment, String link) {
        log.warn("[workflows][no-smtp] workflow '{}' on '{}' cancelled by {} — would notify {}", instance.getDefinitionName(),
                instance.getDocumentName(), actorEmail, toEmail);
    }

    @Override
    public void sendStateReached(WorkflowInstance instance, String stateLabel, String toEmail, String link) {
        log.warn("[workflows][no-smtp] '{}' reached '{}' — would notify {}", instance.getDocumentName(), stateLabel, toEmail);
    }
}
