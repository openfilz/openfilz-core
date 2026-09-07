package org.openfilz.dms.enums;

/** Who has to act when a document reaches a status. */
public enum WorkflowAssigneeType {
    /** The person who started the instance. */
    INITIATOR,
    /** A fixed list of e-mail addresses. */
    USERS,
    /** Any user holding a realm role (e.g. CONTRIBUTOR). */
    ROLE,
    /** The person starting the instance names the assignees in the start dialog. */
    CHOSEN_AT_START
}
