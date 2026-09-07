package org.openfilz.dms.dto.audit;

import java.util.UUID;

/**
 * "This action is part of a workflow" — carried in the reactive context around the actions a
 * workflow performs on a document (move, metadata), and stamped by the audit service onto the
 * entry each of those actions writes.
 * <p>
 * It is deliberately <em>not</em> an actor: the audit trail keeps naming the person who caused the
 * action — whoever took the transition, or the uploader for a hot folder — because that is who is
 * accountable. This only adds the missing half, that they did it through a workflow rather than by
 * hand. Passing it through the context rather than through {@code DocumentService} signatures keeps
 * the document layer unaware of workflows, and covers every action the engine can ever trigger.
 *
 * @param instanceId the running instance
 * @param workflow   the definition's name, as it was when the instance started
 * @param state      label of the status whose entry triggered the action
 */
public record WorkflowAuditCause(UUID instanceId, String workflow, String state) {

    /** Reactive-context key. */
    public static final String CONTEXT_KEY = "openfilz.workflow.auditCause";
}
