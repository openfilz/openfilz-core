package org.openfilz.dms.service.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.workflow.WorkflowReview;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.enums.WorkflowReviewRule;
import org.openfilz.dms.enums.WorkflowTransitionStyle;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowReviewDeciderTest {

    /** Spec order matters: "reject" is listed before "changes", so it outranks it. */
    private static final List<WorkflowTransition> TRANSITIONS = List.of(
            new WorkflowTransition("approve", "Approve", "approved", WorkflowTransitionStyle.SUCCESS, false),
            new WorkflowTransition("reject", "Reject", "rejected", WorkflowTransitionStyle.DANGER, true),
            new WorkflowTransition("changes", "Request changes", "draft", WorkflowTransitionStyle.NEUTRAL, true));

    private static Optional<String> decide(WorkflowReviewRule rule, Integer quorum, int pending, String... votes) {
        return WorkflowReviewDecider.decide(new WorkflowReview(rule, quorum, "approve"), TRANSITIONS, List.of(votes), pending)
                .map(WorkflowTransition::key);
    }

    @Test
    void all_waits_for_everyone_then_needs_unanimity() {
        assertThat(decide(WorkflowReviewRule.ALL, null, 2, "approve")).isEmpty();
        // A rejection does not end the round: the others still get to comment.
        assertThat(decide(WorkflowReviewRule.ALL, null, 1, "changes", "approve")).isEmpty();
        assertThat(decide(WorkflowReviewRule.ALL, null, 0, "approve", "approve", "approve")).contains("approve");
        assertThat(decide(WorkflowReviewRule.ALL, null, 0, "approve", "changes", "approve")).contains("changes");
        assertThat(decide(WorkflowReviewRule.ALL, null, 0, "changes", "reject", "approve")).contains("reject");
    }

    @Test
    void first_rejection_decides_at_once() {
        assertThat(decide(WorkflowReviewRule.FIRST_REJECTION, null, 2, "approve")).isEmpty();
        assertThat(decide(WorkflowReviewRule.FIRST_REJECTION, null, 2, "changes")).contains("changes");
        assertThat(decide(WorkflowReviewRule.FIRST_REJECTION, null, 1, "approve", "reject")).contains("reject");
        assertThat(decide(WorkflowReviewRule.FIRST_REJECTION, null, 0, "approve", "approve", "approve")).contains("approve");
    }

    @Test
    void quorum_approves_as_soon_as_reached_and_rejects_once_out_of_reach() {
        // 2 of 4.
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 3, "approve")).isEmpty();
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 2, "approve", "approve")).contains("approve");
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 2, "reject", "approve")).isEmpty();
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 2, "reject", "changes")).isEmpty();
        // Three said no, one left: two approvals can no longer happen.
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 1, "changes", "reject", "changes")).contains("reject");
        assertThat(decide(WorkflowReviewRule.QUORUM, 2, 0, "changes", "approve", "changes", "changes")).contains("changes");
    }

    @Test
    void an_approval_only_review_just_collects_everyone() {
        List<WorkflowTransition> only = List.of(TRANSITIONS.getFirst());
        WorkflowReview review = new WorkflowReview(WorkflowReviewRule.ALL, null, "approve");
        assertThat(WorkflowReviewDecider.decide(review, only, List.of("approve"), 1)).isEmpty();
        assertThat(WorkflowReviewDecider.decide(review, only, List.of("approve", "approve"), 0))
                .map(WorkflowTransition::key).contains("approve");
    }
}
