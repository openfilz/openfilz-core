package org.openfilz.dms.enums;

/** How the votes of a parallel review turn into the one transition the document takes (docs/workflows.md §3). */
public enum WorkflowReviewRule {
    /** Wait for every reviewer; unanimous approval approves, anything else takes the first rejection voted. */
    ALL,
    /** The first vote that is not the approval decides at once; approval needs everyone. */
    FIRST_REJECTION,
    /** {@code quorum} approvals approve at once; once they can no longer be reached, the first rejection voted decides. */
    QUORUM
}
