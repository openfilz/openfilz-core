package org.openfilz.dms.service.workflow;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowProblem;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.enums.WorkflowActionType;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowStateKind;
import org.openfilz.dms.enums.WorkflowTransitionStyle;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowSpecValidatorTest {

    private static WorkflowState state(String key, WorkflowStateKind kind, WorkflowAssignment a, List<WorkflowTransition> t) {
        return new WorkflowState(key, key, kind, "#112233", a, null, t, List.of());
    }

    private static WorkflowTransition to(String key, String target) {
        return new WorkflowTransition(key, key, target, WorkflowTransitionStyle.PRIMARY, false);
    }

    private static WorkflowSpec approval() {
        return new WorkflowSpec(List.of(
                state("draft", WorkflowStateKind.START, null, List.of(to("submit", "review"))),
                state("review", WorkflowStateKind.STEP, new WorkflowAssignment(WorkflowAssigneeType.USERS, List.of("a@x.com"), null, null),
                        List.of(to("approve", "approved"), to("reject", "rejected"))),
                state("approved", WorkflowStateKind.END, null, List.of()),
                state("rejected", WorkflowStateKind.END, null, List.of())));
    }

    private static List<String> codes(WorkflowSpec spec) {
        return WorkflowSpecValidator.validate(spec, 30, List.of()).stream().map(WorkflowProblem::code).toList();
    }

    @Test
    void a_well_formed_approval_is_valid() {
        assertThat(WorkflowSpecValidator.validate(approval(), 30, List.of())).isEmpty();
    }

    @Test
    void needs_exactly_one_start_and_an_end() {
        WorkflowSpec noStart = new WorkflowSpec(List.of(
                state("a", WorkflowStateKind.STEP, null, List.of(to("t", "b"))),
                state("b", WorkflowStateKind.END, null, List.of())));
        assertThat(codes(noStart)).contains("ONE_START");
        WorkflowSpec noEnd = new WorkflowSpec(List.of(
                state("a", WorkflowStateKind.START, null, List.of(to("t", "a")))));
        assertThat(codes(noEnd)).contains("NO_END");
    }

    @Test
    void rejects_bad_keys_duplicates_and_unknown_targets() {
        WorkflowSpec spec = new WorkflowSpec(List.of(
                state("Draft!", WorkflowStateKind.START, null, List.of(to("go", "nowhere"))),
                state("end", WorkflowStateKind.END, null, List.of()),
                state("end", WorkflowStateKind.END, null, List.of())));
        assertThat(codes(spec)).contains("BAD_KEY", "DUPLICATE_KEY", "UNKNOWN_TARGET");
    }

    @Test
    void end_states_carry_nothing() {
        WorkflowSpec spec = new WorkflowSpec(List.of(
                state("s", WorkflowStateKind.START, null, List.of(to("go", "e"))),
                new WorkflowState("e", "e", WorkflowStateKind.END, null, WorkflowAssignment.initiator(), 3, List.of(to("x", "s")), List.of())));
        assertThat(codes(spec)).contains("END_HAS_TRANSITIONS", "END_HAS_ASSIGNEES", "END_HAS_DUE");
    }

    @Test
    void unreachable_and_dead_end_states_are_reported() {
        WorkflowSpec spec = new WorkflowSpec(List.of(
                state("s", WorkflowStateKind.START, null, List.of(to("go", "e"))),
                state("orphan", WorkflowStateKind.STEP, null, List.of(to("go", "e"))),
                state("e", WorkflowStateKind.END, null, List.of())));
        assertThat(codes(spec)).contains("UNREACHABLE");
        WorkflowSpec loop = new WorkflowSpec(List.of(
                state("s", WorkflowStateKind.START, null, List.of(to("go", "l"), to("done", "e"))),
                state("l", WorkflowStateKind.STEP, null, List.of(to("again", "l"))),
                state("e", WorkflowStateKind.END, null, List.of())));
        assertThat(codes(loop)).contains("DEAD_END");
    }

    @Test
    void assignees_and_actions_are_checked() {
        WorkflowSpec spec = new WorkflowSpec(List.of(
                state("s", WorkflowStateKind.START, null, List.of(to("go", "r"))),
                new WorkflowState("r", "r", WorkflowStateKind.STEP, null,
                        new WorkflowAssignment(WorkflowAssigneeType.USERS, List.of("not-an-email"), null, null), 400,
                        List.of(to("ok", "e")),
                        List.of(new WorkflowAction(WorkflowActionType.MOVE_TO_FOLDER, null, null, null),
                                new WorkflowAction(WorkflowActionType.SET_METADATA, null, Map.of("_signed", "x"), null),
                                new WorkflowAction(WorkflowActionType.NOTIFY, null, null, List.of()))),
                state("e", WorkflowStateKind.END, null, List.of())));
        assertThat(codes(spec)).contains("BAD_EMAIL", "BAD_DUE", "NO_FOLDER", "BAD_KEY", "NO_EMAIL");
    }

    @Test
    void hot_folders_cannot_ask_the_starter_to_choose() {
        WorkflowSpec spec = new WorkflowSpec(List.of(
                state("s", WorkflowStateKind.START, null, List.of(to("go", "r"))),
                state("r", WorkflowStateKind.STEP, new WorkflowAssignment(WorkflowAssigneeType.CHOSEN_AT_START, null, null, "Approver"),
                        List.of(to("ok", "e"))),
                state("e", WorkflowStateKind.END, null, List.of())));
        assertThat(WorkflowSpecValidator.validate(spec, 30, List.of())).isEmpty();
        assertThat(WorkflowSpecValidator.validate(spec, 30, List.of(UUID.randomUUID())).stream().map(WorkflowProblem::code))
                .contains("TRIGGER_NEEDS_FIXED_ASSIGNEES");
    }

    @Test
    void too_many_states_is_a_problem() {
        assertThat(WorkflowSpecValidator.validate(approval(), 2, List.of()).stream().map(WorkflowProblem::code)).contains("TOO_MANY");
    }
}
