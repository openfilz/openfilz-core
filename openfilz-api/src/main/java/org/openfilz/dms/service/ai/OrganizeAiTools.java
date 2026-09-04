package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.response.ReorganizationPlanView.Item;
import org.openfilz.dms.dto.response.ReorganizationPlanView.ItemResult;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.exception.AbstractOpenFilzException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

/**
 * Document-reorganisation tools, shared by the in-app assistant and the MCP server. The loop is
 * <em>inventory → the model proposes → the backend validates and stores the proposal → the user
 * confirms → apply</em>:
 * <ol>
 *   <li>{@link #planReorganization} hands the model a compact inventory of a folder subtree and
 *       the JSON contract of a plan;</li>
 *   <li>{@link #proposeReorganizationPlan} validates the model's plan against the live state and
 *       the caller's permissions, persists it and reports what is applicable and what is blocked
 *       (and why) — nothing moves yet;</li>
 *   <li>{@link #applyReorganizationPlan} moves the confirmed items. In the OpenFilz app the user
 *       confirms from a proposal card (which calls the REST endpoint); an external agent calls
 *       this tool once its user has confirmed.</li>
 * </ol>
 * Built per request by {@code OrganizeAiToolsContributor}, bound to the caller with
 * {@link #forUser}; reports its side effects through {@link AiToolTurnEffects}.
 */
@Slf4j
public class OrganizeAiTools implements AiToolTurnEffects {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_LISTED_ITEMS = 60;

    private final ReorganizationPlanService service;
    private final AiToolRolePolicy rolePolicy;

    private String userEmail;
    private Authentication authentication;
    private UUID conversationId;

    private final Set<String> modifiedFolders = ConcurrentHashMap.newKeySet();
    private final List<String> performedActions = new CopyOnWriteArrayList<>();
    private final List<UUID> proposedPlanIds = new CopyOnWriteArrayList<>();

    public OrganizeAiTools(ReorganizationPlanService service, AiToolRolePolicy rolePolicy) {
        this.service = service;
        this.rolePolicy = rolePolicy;
    }

    public OrganizeAiTools forUser(String userEmail, Authentication authentication) {
        this.userEmail = userEmail;
        this.authentication = authentication;
        return this;
    }

    @Override
    public void bindConversation(UUID conversationId) {
        this.conversationId = conversationId;
    }

    @Override
    public Set<String> modifiedFolders() {
        return modifiedFolders;
    }

    @Override
    public List<String> performedActions() {
        return performedActions;
    }

    @Override
    public List<UUID> proposedPlanIds() {
        return proposedPlanIds;
    }

    // ── tools ───────────────────────────────────────────────────────────────

    @Tool(description = "Start reorganising documents: get an inventory of a folder (its sub-folders and files with "
            + "ids, paths, types, sizes, dates and metadata) plus the JSON contract for proposing a new folder "
            + "hierarchy. Use it when the user asks to tidy, sort, classify, restructure or reorganise their "
            + "documents. Then design the hierarchy and call proposeReorganizationPlan.")
    public String planReorganization(
            @ToolParam(required = false, description = "Name (or id) of the folder to reorganise; null or 'root' for the root level") String folder,
            @ToolParam(required = false, description = "How many levels deep to inventory (default 4, max 10)") Integer maxDepth,
            @ToolParam(required = false, description = "Maximum entries to list (default 300, max 1000)") Integer maxItems,
            @ToolParam(required = false, description = "'full' includes each file's AI summary, 'compact' leaves it out; "
                    + "default: full under 300 files, compact above") String detail) {
        String denial = deny("planReorganization", ToolCapability.DOCUMENT_READ);
        if (denial != null) return denial;
        return run(() -> {
            UUID rootId = null;
            if (folder != null && !folder.isBlank() && !"root".equalsIgnoreCase(folder.trim())
                    && !"null".equalsIgnoreCase(folder.trim()) && !"/".equals(folder.trim())) {
                Document root = service.resolveDocument(folder, caller(), new HashMap<>());
                if (root == null || !"FOLDER".equals(root.getType().name())) {
                    return "No folder '" + folder + "' is visible to you. Use queryDocuments to find the exact name, or omit the folder for the root level.";
                }
                rootId = root.getId();
            }
            String inventory = service.inventory(rootId, maxDepth, maxItems, detail, caller());
            return inventory + "\n" + PLAN_CONTRACT.replace("{root}", rootId != null ? "\"" + rootId + "\"" : "null");
        });
    }

    private static final String PLAN_CONTRACT = """
            HOW TO PROPOSE A REORGANISATION
            Use the inventory's insights (category, language, summary), dates and activity to decide the logic — \
            by category, client, project, year, or active vs archive — before reading any file. \
            Design a clear, shallow hierarchy (2-3 levels; group by topic, project, client, year, document type…), \
            then call proposeReorganizationPlan with this JSON:
            {"rootFolder": {root}, "rationale": "<one sentence on the logic of the hierarchy>", \
            "moves": [{"document": "<file or folder id from the inventory>", "target": "Finance/Invoices/2026"}], \
            "createFolders": ["<optional extra folder path>"]}
            Rules: target paths are relative to the root folder and missing folders are created automatically; \
            an empty target means the root folder itself; move documents by id; leave out documents that are \
            already well placed; existing sub-folders can be moved too; use readDocumentContent when a name \
            alone does not tell what a file is; never invent ids. \
            The plan is only a proposal — nothing moves until the user confirms it.""";

    @Tool(description = "Submit a reorganisation plan (JSON, see planReorganization) for validation. Nothing is "
            + "moved: the backend checks every move against the live library and the user's permissions, stores "
            + "the plan and returns its id with what is applicable and what is blocked. The user then confirms "
            + "(in the OpenFilz app through a proposal card; otherwise ask them, then call applyReorganizationPlan).")
    public String proposeReorganizationPlan(
            @ToolParam(description = "The plan as a JSON object: {\"rootFolder\": <id or null>, \"rationale\": \"...\", "
                    + "\"moves\": [{\"document\": \"<id>\", \"target\": \"Folder/Subfolder\"}], \"createFolders\": [...]}") String planJson) {
        String denial = deny("proposeReorganizationPlan", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            ReorganizationPlanRequest request = parsePlan(planJson);
            if (request == null) {
                return "The plan must be a JSON object with a 'moves' array — see planReorganization for the contract.";
            }
            ReorganizationPlanView view = service.propose(request, conversationId, caller());
            if (view.id() == null) {
                return "Nothing in this plan can be applied:\n" + renderItems(view)
                        + "\nFix the plan (use ids from planReorganization) and propose it again.";
            }
            proposedPlanIds.add(view.id());
            return render(view) + "\n\nNEXT: the plan is stored as a proposal and NOTHING has moved yet. "
                    + "Summarise the proposed hierarchy to the user; they can review and apply it from the "
                    + "proposal card shown in the OpenFilz app. Only call applyReorganizationPlan yourself if the "
                    + "user explicitly confirms in this conversation. Plan id: " + view.id();
        });
    }

    @Tool(description = "Apply a proposed reorganisation plan (all its applicable items, or only the given "
            + "documents) once the user has confirmed: creates the missing folders and moves the documents. "
            + "Reports what moved and what failed.")
    public String applyReorganizationPlan(
            @ToolParam(description = "Id of the plan returned by proposeReorganizationPlan") String planId,
            @ToolParam(required = false, description = "Comma-separated document ids to apply; omit to apply every applicable item") String documentIds) {
        String denial = deny("applyReorganizationPlan", ToolCapability.DOCUMENT_WRITE);
        if (denial != null) return denial;
        return run(() -> {
            UUID id = parseUuid(planId);
            if (id == null) return "planId must be the id of a proposed plan.";
            List<UUID> selected = null;
            if (documentIds != null && !documentIds.isBlank()) {
                selected = new ArrayList<>();
                for (String token : documentIds.split("[,;\\s]+")) {
                    UUID docId = parseUuid(token);
                    if (docId == null) return "documentIds must be document ids, got '" + token + "'.";
                    selected.add(docId);
                }
            }
            ReorganizationApplyResult result = service.apply(id, selected, caller());
            modifiedFolders.addAll(result.modifiedFolderIds());
            if (!result.createdFolders().isEmpty()) {
                performedActions.add("Created " + result.createdFolders().size() + " folder(s): "
                        + String.join(", ", result.createdFolders()));
            }
            if (result.moved() > 0) {
                performedActions.add("Moved " + result.moved() + " document(s) according to reorganisation plan " + id);
            }
            StringBuilder sb = new StringBuilder();
            sb.append("Plan ").append(id).append(" ").append(result.status().toLowerCase().replace('_', ' '))
                    .append(": ").append(result.moved()).append(" moved, ").append(result.failed()).append(" failed, ")
                    .append(result.skipped()).append(" skipped.");
            if (!result.createdFolders().isEmpty()) {
                sb.append(" Created folders: ").append(String.join(", ", result.createdFolders())).append('.');
            }
            List<ItemResult> failures = result.plan().results().stream().filter(r -> "FAILED".equals(r.outcome())).toList();
            if (!failures.isEmpty()) {
                Map<UUID, Item> byId = result.plan().items().stream().filter(i -> i.documentId() != null)
                        .collect(Collectors.toMap(Item::documentId, i -> i, (a, b) -> a));
                sb.append("\nFailures:");
                for (ItemResult failure : failures) {
                    Item item = byId.get(failure.documentId());
                    sb.append("\n  - ").append(item != null ? item.name() : failure.documentId()).append(": ").append(failure.detail());
                }
            }
            return sb.toString();
        });
    }

    @Tool(description = "Show a reorganisation plan: its status (proposed, applied, discarded…), the moves and "
            + "their outcome. Use it to check whether the user has applied a proposal.")
    public String getReorganizationPlan(
            @ToolParam(description = "Id of the plan") String planId) {
        String denial = deny("getReorganizationPlan", ToolCapability.DOCUMENT_READ);
        if (denial != null) return denial;
        return run(() -> {
            UUID id = parseUuid(planId);
            if (id == null) return "planId must be the id of a plan.";
            return render(service.get(id, caller()));
        });
    }

    // ── rendering ───────────────────────────────────────────────────────────

    static String render(ReorganizationPlanView view) {
        StringBuilder sb = new StringBuilder();
        sb.append("Plan ").append(view.id()).append(" (").append(view.status()).append("): ")
                .append(view.applicable()).append(" move(s) ready, ").append(view.blocked()).append(" blocked. Root: ")
                .append(view.rootFolderPath()).append('.');
        if (view.rationale() != null) sb.append(" Rationale: ").append(view.rationale());
        if (!view.foldersToCreate().isEmpty()) {
            sb.append("\nFolders to create: ").append(String.join(", ", view.foldersToCreate()));
        }
        sb.append("\nMoves:\n").append(renderItems(view));
        if (view.results() != null) {
            long moved = view.results().stream().filter(r -> "MOVED".equals(r.outcome())).count();
            long failed = view.results().stream().filter(r -> "FAILED".equals(r.outcome())).count();
            sb.append("Outcome: ").append(moved).append(" moved, ").append(failed).append(" failed");
            if (view.appliedAt() != null) sb.append(" (applied ").append(view.appliedAt().toLocalDate()).append(')');
            sb.append('.');
        }
        return sb.toString();
    }

    private static String renderItems(ReorganizationPlanView view) {
        Map<UUID, ItemResult> results = new HashMap<>();
        if (view.results() != null) {
            view.results().stream().filter(r -> r.documentId() != null).forEach(r -> results.put(r.documentId(), r));
        }
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Item item : view.items()) {
            if (shown++ >= MAX_LISTED_ITEMS) {
                sb.append("  … ").append(view.items().size() - MAX_LISTED_ITEMS).append(" more\n");
                break;
            }
            sb.append("  - ").append(item.name());
            if (item.type() != null) sb.append(" (").append(item.type().toLowerCase()).append(')');
            if (item.currentPath() != null && item.targetPath() != null) {
                sb.append(": ").append(item.currentPath()).append(" → ").append(item.targetPath());
                if (!item.targetExists()) sb.append(" (new folder)");
            }
            ItemResult result = item.documentId() != null ? results.get(item.documentId()) : null;
            if (result != null) {
                sb.append("  [").append(result.outcome().toLowerCase());
                if (result.detail() != null) sb.append(": ").append(result.detail());
                sb.append(']');
            } else {
                sb.append(item.applicable() ? "  [ready]" : "  [blocked: " + item.issue() + "]");
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private interface ToolBody {
        String call() throws Exception;
    }

    private String run(ToolBody body) {
        try {
            return body.call();
        } catch (ResponseStatusException e) {
            log.debug("[AI-TOOL] reorganisation tool refused: {}", e.getReason());
            return "Could not perform the operation: " + (e.getReason() != null ? e.getReason() : e.getMessage());
        } catch (AbstractOpenFilzException | IllegalArgumentException | IllegalStateException e) {
            log.debug("[AI-TOOL] reorganisation tool refused: {}", e.getMessage());
            return "Could not perform the operation: " + e.getMessage();
        } catch (Exception e) {
            log.error("[AI-TOOL] reorganisation tool failed", e);
            return "Error: " + e.getMessage();
        }
    }

    private String deny(String toolName, ToolCapability capability) {
        if (rolePolicy == null || rolePolicy.isAllowed(authentication, capability)) {
            return null;
        }
        log.warn("[AI-TOOL] {} refused: caller lacks the role for {}", toolName, capability);
        return "Not permitted: your OpenFilz role does not allow this operation (" + capability
                + "). Ask an administrator for the required role.";
    }

    private ReorganizationPlanService.Caller caller() {
        return new ReorganizationPlanService.Caller(userEmail, authentication);
    }

    /** Parse the plan JSON, tolerating a Markdown code fence around it. */
    static ReorganizationPlanRequest parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) return null;
        String json = planJson.trim();
        if (json.startsWith("```")) {
            json = json.substring(json.indexOf('\n') + 1);
            int end = json.lastIndexOf("```");
            if (end >= 0) json = json.substring(0, end);
            json = json.trim();
        }
        try {
            ReorganizationPlanRequest request = JSON.readValue(json, ReorganizationPlanRequest.class);
            return request != null && request.moves() != null ? request : null;
        } catch (Exception e) {
            throw new IllegalArgumentException("The plan is not valid JSON: " + e.getMessage());
        }
    }

    private static UUID parseUuid(String value) {
        if (value == null) return null;
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
