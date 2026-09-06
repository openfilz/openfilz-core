package org.openfilz.dms.enums;

/** Append-only history entries of a workflow instance. */
public enum WorkflowEventType {
    STARTED, TRANSITIONED, ACTION_APPLIED, ACTION_FAILED, REASSIGNED, REMINDED, COMPLETED, CANCELLED
}
