package org.openfilz.dms.e2e.workflow;

import org.openfilz.dms.entity.WorkflowInstance;
import org.openfilz.dms.entity.WorkflowTask;
import org.openfilz.dms.service.workflow.WorkflowMailer;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/** Records every workflow mail instead of sending it, so the ITs can assert on invitations and reminders. */
public class CapturingWorkflowMailer implements WorkflowMailer {

    public record Sent(String kind, UUID instanceId, UUID taskId, String to, String link, String comment) {}

    private final List<Sent> sent = new CopyOnWriteArrayList<>();

    @Override
    public void sendTaskAssigned(WorkflowInstance i, WorkflowTask t, String toEmail, String link, String previousComment) {
        sent.add(new Sent("task", i.getId(), t.getId(), toEmail, link, previousComment));
    }

    @Override
    public void sendTaskOverdue(WorkflowInstance i, WorkflowTask t, String toEmail, String link) {
        sent.add(new Sent("overdue", i.getId(), t.getId(), toEmail, link, null));
    }

    @Override
    public void sendCompleted(WorkflowInstance i, String toEmail, String link) {
        sent.add(new Sent("completed", i.getId(), null, toEmail, link, null));
    }

    @Override
    public void sendCancelled(WorkflowInstance i, String toEmail, String actorEmail, String comment, String link) {
        sent.add(new Sent("cancelled", i.getId(), null, toEmail, link, comment));
    }

    @Override
    public void sendStateReached(WorkflowInstance i, String stateLabel, String toEmail, String link) {
        sent.add(new Sent("reached", i.getId(), null, toEmail, link, stateLabel));
    }

    public List<Sent> all() {
        return List.copyOf(sent);
    }

    public List<Sent> ofKind(String kind) {
        return sent.stream().filter(s -> s.kind().equals(kind)).toList();
    }

    public List<Sent> to(String email) {
        return sent.stream().filter(s -> s.to().equalsIgnoreCase(email)).toList();
    }

    public void clear() {
        sent.clear();
    }
}
