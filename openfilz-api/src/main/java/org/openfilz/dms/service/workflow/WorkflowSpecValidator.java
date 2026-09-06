package org.openfilz.dms.service.workflow;

import org.openfilz.dms.dto.workflow.WorkflowAction;
import org.openfilz.dms.dto.workflow.WorkflowAssignment;
import org.openfilz.dms.dto.workflow.WorkflowProblem;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import org.openfilz.dms.dto.workflow.WorkflowState;
import org.openfilz.dms.dto.workflow.WorkflowTransition;
import org.openfilz.dms.enums.WorkflowAssigneeType;
import org.openfilz.dms.enums.WorkflowStateKind;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Structural rules of a {@link WorkflowSpec} (docs/workflows.md §3). Pure and static so the
 * same rules run in unit tests and, mirrored, in the web designer. Every problem carries a
 * JSON-pointer-like {@code path} the designer can highlight.
 */
public final class WorkflowSpecValidator {

    public static final Pattern KEY = Pattern.compile("^[a-z0-9_]{1,40}$");
    public static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    public static final int MAX_LABEL = 100;
    public static final int MAX_TRANSITION_LABEL = 60;
    public static final int MAX_METADATA_ENTRIES = 20;
    public static final int MAX_DUE_DAYS = 365;

    private WorkflowSpecValidator() {}

    public static List<WorkflowProblem> validate(WorkflowSpec spec, int maxStates, List<UUID> triggerFolderIds) {
        List<WorkflowProblem> problems = new ArrayList<>();
        if (spec == null || spec.states().isEmpty()) {
            problems.add(new WorkflowProblem("states", "EMPTY", "A workflow needs at least one status"));
            return problems;
        }
        List<WorkflowState> states = spec.states();
        if (states.size() > maxStates) {
            problems.add(new WorkflowProblem("states", "TOO_MANY", "At most " + maxStates + " statuses"));
        }
        Map<String, WorkflowState> byKey = new HashMap<>();
        int starts = 0, ends = 0;
        boolean chosenAtStart = false;
        for (int i = 0; i < states.size(); i++) {
            WorkflowState s = states.get(i);
            String p = "states[" + i + "]";
            if (s == null) {
                problems.add(new WorkflowProblem(p, "NULL", "Empty status"));
                continue;
            }
            if (s.key() == null || !KEY.matcher(s.key()).matches()) {
                problems.add(new WorkflowProblem(p + ".key", "BAD_KEY", "Status key must match " + KEY.pattern()));
            } else if (byKey.put(s.key(), s) != null) {
                problems.add(new WorkflowProblem(p + ".key", "DUPLICATE_KEY", "Duplicate status key '" + s.key() + "'"));
            }
            if (s.label() == null || s.label().isBlank() || s.label().length() > MAX_LABEL) {
                problems.add(new WorkflowProblem(p + ".label", "BAD_LABEL", "Status label is required (max " + MAX_LABEL + " chars)"));
            }
            if (s.kind() == null) {
                problems.add(new WorkflowProblem(p + ".kind", "BAD_KIND", "Status kind must be START, STEP or END"));
                continue;
            }
            if (s.kind() == WorkflowStateKind.START) starts++;
            if (s.kind() == WorkflowStateKind.END) ends++;
            if (s.color() != null && !s.color().isBlank() && !s.color().matches("^#[0-9a-fA-F]{6}$")) {
                problems.add(new WorkflowProblem(p + ".color", "BAD_COLOR", "Colour must be #rrggbb"));
            }
            if (s.isEnd()) {
                if (!s.transitions().isEmpty()) {
                    problems.add(new WorkflowProblem(p + ".transitions", "END_HAS_TRANSITIONS", "A final status has no transitions"));
                }
                if (s.assignees() != null) {
                    problems.add(new WorkflowProblem(p + ".assignees", "END_HAS_ASSIGNEES", "A final status has no assignees"));
                }
                if (s.dueInDays() != null) {
                    problems.add(new WorkflowProblem(p + ".dueInDays", "END_HAS_DUE", "A final status has no due delay"));
                }
            } else {
                if (s.transitions().isEmpty()) {
                    problems.add(new WorkflowProblem(p + ".transitions", "NO_TRANSITION", "Status '" + s.label() + "' needs at least one transition"));
                }
                WorkflowAssignment a = s.effectiveAssignees();
                chosenAtStart |= validateAssignment(a, p + ".assignees", problems);
                if (s.dueInDays() != null && (s.dueInDays() < 1 || s.dueInDays() > MAX_DUE_DAYS)) {
                    problems.add(new WorkflowProblem(p + ".dueInDays", "BAD_DUE", "Due delay must be between 1 and " + MAX_DUE_DAYS + " days"));
                }
            }
            Set<String> tKeys = new HashSet<>();
            for (int j = 0; j < s.transitions().size(); j++) {
                WorkflowTransition t = s.transitions().get(j);
                String tp = p + ".transitions[" + j + "]";
                if (t == null) {
                    problems.add(new WorkflowProblem(tp, "NULL", "Empty transition"));
                    continue;
                }
                if (t.key() == null || !KEY.matcher(t.key()).matches()) {
                    problems.add(new WorkflowProblem(tp + ".key", "BAD_KEY", "Transition key must match " + KEY.pattern()));
                } else if (!tKeys.add(t.key())) {
                    problems.add(new WorkflowProblem(tp + ".key", "DUPLICATE_KEY", "Duplicate transition key '" + t.key() + "'"));
                }
                if (t.label() == null || t.label().isBlank() || t.label().length() > MAX_TRANSITION_LABEL) {
                    problems.add(new WorkflowProblem(tp + ".label", "BAD_LABEL", "Transition label is required (max " + MAX_TRANSITION_LABEL + " chars)"));
                }
                if (t.to() == null || t.to().isBlank()) {
                    problems.add(new WorkflowProblem(tp + ".to", "NO_TARGET", "Transition needs a target status"));
                }
            }
            for (int j = 0; j < s.onEnter().size(); j++) {
                validateAction(s.onEnter().get(j), p + ".onEnter[" + j + "]", problems);
            }
        }
        if (starts != 1) {
            problems.add(new WorkflowProblem("states", "ONE_START", "Exactly one status must be the START"));
        }
        if (ends == 0) {
            problems.add(new WorkflowProblem("states", "NO_END", "At least one status must be an END"));
        }
        // Targets must exist (second pass, once every key is known).
        for (int i = 0; i < states.size(); i++) {
            WorkflowState s = states.get(i);
            if (s == null) continue;
            for (int j = 0; j < s.transitions().size(); j++) {
                WorkflowTransition t = s.transitions().get(j);
                if (t != null && t.to() != null && !t.to().isBlank() && !byKey.containsKey(t.to())) {
                    problems.add(new WorkflowProblem("states[" + i + "].transitions[" + j + "].to", "UNKNOWN_TARGET",
                            "Unknown target status '" + t.to() + "'"));
                }
            }
        }
        if (triggerFolderIds != null && !triggerFolderIds.isEmpty() && chosenAtStart) {
            problems.add(new WorkflowProblem("triggerFolderIds", "TRIGGER_NEEDS_FIXED_ASSIGNEES",
                    "A workflow started automatically from a folder cannot ask the starter to choose assignees"));
        }
        if (problems.isEmpty()) {
            reachability(spec, byKey, problems);
        }
        return problems;
    }

    /** @return true when the assignment is CHOSEN_AT_START. */
    private static boolean validateAssignment(WorkflowAssignment a, String p, List<WorkflowProblem> problems) {
        if (a.type() == null) {
            problems.add(new WorkflowProblem(p + ".type", "BAD_ASSIGNEE_TYPE", "Assignee type is required"));
            return false;
        }
        switch (a.type()) {
            case USERS -> {
                if (a.emails().isEmpty()) {
                    problems.add(new WorkflowProblem(p + ".emails", "NO_EMAIL", "Name at least one e-mail address"));
                }
                for (String e : a.emails()) {
                    if (!EMAIL.matcher(e).matches()) {
                        problems.add(new WorkflowProblem(p + ".emails", "BAD_EMAIL", "Invalid e-mail address '" + e + "'"));
                    }
                }
            }
            case ROLE -> {
                if (a.role() == null || !a.role().matches("^[A-Za-z0-9_\\-]{1,64}$")) {
                    problems.add(new WorkflowProblem(p + ".role", "BAD_ROLE", "Role name is required"));
                }
            }
            case CHOSEN_AT_START -> {
                return true;
            }
            case INITIATOR -> { /* nothing to check */ }
        }
        return false;
    }

    private static void validateAction(WorkflowAction a, String p, List<WorkflowProblem> problems) {
        if (a == null || a.type() == null) {
            problems.add(new WorkflowProblem(p + ".type", "BAD_ACTION_TYPE", "Action type is required"));
            return;
        }
        switch (a.type()) {
            case MOVE_TO_FOLDER -> {
                if (a.folderId() == null) {
                    problems.add(new WorkflowProblem(p + ".folderId", "NO_FOLDER", "Choose the destination folder"));
                }
            }
            case SET_METADATA -> {
                if (a.entries().isEmpty()) {
                    problems.add(new WorkflowProblem(p + ".entries", "NO_ENTRIES", "Name at least one metadata key"));
                }
                if (a.entries().size() > MAX_METADATA_ENTRIES) {
                    problems.add(new WorkflowProblem(p + ".entries", "TOO_MANY_ENTRIES", "At most " + MAX_METADATA_ENTRIES + " metadata keys"));
                }
                for (String k : a.entries().keySet()) {
                    if (k == null || k.isBlank() || k.startsWith("_") || k.length() > 100) {
                        problems.add(new WorkflowProblem(p + ".entries", "BAD_KEY", "Metadata keys must not be empty, start with '_' or exceed 100 chars"));
                        break;
                    }
                }
            }
            case NOTIFY -> {
                if (a.emails().isEmpty()) {
                    problems.add(new WorkflowProblem(p + ".emails", "NO_EMAIL", "Name at least one e-mail address"));
                }
                for (String e : a.emails()) {
                    if (!EMAIL.matcher(e).matches()) {
                        problems.add(new WorkflowProblem(p + ".emails", "BAD_EMAIL", "Invalid e-mail address '" + e + "'"));
                    }
                }
            }
        }
    }

    /** Every status reachable from START; every non-END status able to reach an END. */
    private static void reachability(WorkflowSpec spec, Map<String, WorkflowState> byKey, List<WorkflowProblem> problems) {
        WorkflowState start = spec.start().orElseThrow();
        Set<String> seen = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(start.key());
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (!seen.add(k)) continue;
            WorkflowState s = byKey.get(k);
            if (s == null) continue;
            s.transitions().forEach(t -> queue.add(t.to()));
        }
        for (int i = 0; i < spec.states().size(); i++) {
            WorkflowState s = spec.states().get(i);
            if (!seen.contains(s.key())) {
                problems.add(new WorkflowProblem("states[" + i + "]", "UNREACHABLE", "Status '" + s.label() + "' can never be reached"));
            }
        }
        // Reverse reachability from the END states.
        Map<String, Set<String>> reverse = new HashMap<>();
        for (WorkflowState s : spec.states()) {
            for (WorkflowTransition t : s.transitions()) {
                reverse.computeIfAbsent(t.to(), x -> new HashSet<>()).add(s.key());
            }
        }
        Set<String> canFinish = new HashSet<>();
        spec.states().stream().filter(WorkflowState::isEnd).forEach(e -> queue.add(e.key()));
        while (!queue.isEmpty()) {
            String k = queue.poll();
            if (!canFinish.add(k)) continue;
            reverse.getOrDefault(k, Set.of()).forEach(queue::add);
        }
        for (int i = 0; i < spec.states().size(); i++) {
            WorkflowState s = spec.states().get(i);
            if (!s.isEnd() && seen.contains(s.key()) && !canFinish.contains(s.key())) {
                problems.add(new WorkflowProblem("states[" + i + "]", "DEAD_END", "Status '" + s.label() + "' can never reach a final status"));
            }
        }
    }
}
