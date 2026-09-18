package org.openfilz.dms.dto.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.openfilz.dms.enums.WorkflowReviewRule;

/**
 * Makes a STEP a parallel review: one task per reviewer, each voting with one of the status'
 * transitions (and their own comment), combined by {@code rule}. {@code approveTransition} is the
 * key of the transition that counts as approval — every other transition is a rejection of some
 * kind. {@code quorum} is only read by {@link WorkflowReviewRule#QUORUM}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WorkflowReview(WorkflowReviewRule rule, Integer quorum, String approveTransition) {
}
