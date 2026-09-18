package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.WorkflowReview;
import org.openfilz.dms.dto.workflow.WorkflowTransition;

import java.util.List;
import java.util.Optional;

/**
 * Turns the votes of a parallel review into the one transition the document takes
 * (docs/workflows.md §4). Pure: the engine hands it the votes cast so far and how many reviewers
 * have not voted yet, and moves the document only when an outcome comes back.
 * <p>
 * A vote is the key of the transition a reviewer picked. {@code review.approveTransition()} is the
 * approval; any other key is a rejection of some kind (reject, request changes…). When the review
 * ends in a rejection and several kinds were voted, the one listed first on the status wins — the
 * designer orders the transitions, so the designer decides which rejection outranks the other.
 */
public final class WorkflowReviewDecider {

    private WorkflowReviewDecider() {}

    /**
     * @param transitions the review status' transitions, in spec order
     * @param votes       the transition keys voted so far
     * @param pending     reviewers who have not voted yet
     * @return the transition to take, or empty while the review is still open
     */
    public static Optional<WorkflowTransition> decide(WorkflowReview review, List<WorkflowTransition> transitions,
                                                      List<String> votes, int pending) {
        String approve = review.approveTransition();
        long approvals = votes.stream().filter(approve::equals).count();
        boolean rejected = approvals < votes.size();
        Optional<WorkflowTransition> approval = transitions.stream().filter(t -> approve.equals(t.key())).findFirst();
        Optional<WorkflowTransition> rejection = transitions.stream()
                .filter(t -> !approve.equals(t.key()) && votes.contains(t.key()))
                .findFirst();
        return switch (review.rule()) {
            case ALL -> pending > 0 ? Optional.empty() : rejected ? rejection : approval;
            case FIRST_REJECTION -> rejected ? rejection : pending > 0 ? Optional.empty() : approval;
            case QUORUM -> {
                int quorum = review.quorum() == null ? 1 : review.quorum();
                if (approvals >= quorum) yield approval;
                // The quorum is out of reach even if everyone left approves.
                if (approvals + pending < quorum) yield rejected ? rejection : approval;
                yield Optional.empty();
            }
        };
    }
}
