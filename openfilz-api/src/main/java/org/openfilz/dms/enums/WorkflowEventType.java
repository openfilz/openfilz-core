package org.openfilz.dms.enums;

/** Append-only history entries of a workflow instance ({@code REVIEWED} = one reviewer's vote in a parallel review). */
public enum WorkflowEventType {
    STARTED, TRANSITIONED, REVIEWED, ACTION_APPLIED, ACTION_FAILED, REASSIGNED, REMINDED, COMPLETED, CANCELLED
}
