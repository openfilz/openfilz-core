package org.openfilz.dms.dto.workflow;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Body of {@code POST /workflows/instances}. {@code assignments} names the people of every
 * CHOSEN_AT_START status ({@code stateKey → emails}); {@code transitionKey} optionally takes one
 * of the START status' transitions right away ("Start & submit for approval").
 */
public record StartWorkflowRequest(@NotNull UUID definitionId,
                                   @NotNull UUID documentId,
                                   Map<String, List<String>> assignments,
                                   String transitionKey,
                                   @Size(max = 2000) String comment) {
}
