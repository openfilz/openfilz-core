package org.openfilz.dms.dto.workflow;

import org.openfilz.dms.enums.WorkflowReviewRule;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Where a parallel review round stands, carried by each of its tasks: the rule, the votes cast
 * so far (reviewers see each other's comments) and who is still expected to vote.
 */
public record WorkflowReviewProgressDTO(WorkflowReviewRule rule,
                                        Integer quorum,
                                        String approveTransition,
                                        /** Reviewers in this round (one task each). */
                                        int total,
                                        int approvals,
                                        /** Votes cast so far, oldest first. */
                                        List<Vote> votes,
                                        /** E-mails of the reviewers who have not voted yet. */
                                        List<String> pending) {

    public record Vote(String reviewer, String transitionKey, String comment, OffsetDateTime at) {}
}
