package org.openfilz.dms.enums;

/** Kind of a workflow status: exactly one START, any number of STEPs, at least one END. */
public enum WorkflowStateKind {
    START, STEP, END
}
