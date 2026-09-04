package org.openfilz.dms.service.ai;

import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.ReorganizationApplyResult;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.dto.response.ReorganizationPlanView.Item;
import org.openfilz.dms.dto.response.ReorganizationPlanView.ItemResult;
import org.openfilz.dms.entity.AiReorganizationPlan;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.AiReorganizationPlanRepository;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.DocumentService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * AI-assisted document reorganisation: inventory a folder subtree for a model, validate the plan
 * the model proposes, persist it for the user's confirmation, and apply it.
 * <p>
 * The model only decides the taxonomy. Everything that can go wrong is decided here,
 * deterministically, against the live state and the caller's permissions ({@link AiAccessPolicy}
 * for <em>which</em> documents, {@link AiToolRolePolicy} for <em>whether</em> the caller may write
 * at all): unknown documents, documents the caller may not move, folders it may not create in,
 * a folder moved into itself, name clashes in the target, no-op moves. A blocked item never
 * blocks the rest of the plan — it is reported with its reason and skipped on apply.
 * <p>
 * Every mutation goes through {@link DocumentService} (create folder, move files, move folders),
 * so audit, indexing and the enterprise ownership model apply exactly as for a manual move.
 * <p>
 * Methods are blocking: they are called from tool threads (the chat assistant and the MCP
 * server run tools synchronously) and, from the REST controller, on {@code boundedElastic}.
 */
@Slf4j
@Service
@Lazy
@RequiredArgsConstructor
public class ReorganizationPlanService {

    public static final String STATUS_PROPOSED = "PROPOSED";
    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_PARTIALLY_APPLIED = "PARTIALLY_APPLIED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DISCARDED = "DISCARDED";

    public static final int DEFAULT_MAX_DEPTH = 4;
    public static final int MAX_DEPTH = 10;
    public static final int DEFAULT_MAX_ITEMS = 300;
    public static final int MAX_ITEMS = 1000;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DocumentRepository documentRepository;
    private final DocumentService documentService;
    private final AiAccessPolicy accessPolicy;
    private final AiToolRolePolicy rolePolicy;
    private final AiReorganizationPlanRepository planRepository;
    private final ReorganizationInventoryCache inventoryCache;
    private final org.openfilz.dms.repository.AuditDAO auditDAO;
    private final org.openfilz.dms.service.insight.DocumentInsightStore insightStore;

    /** Inventory detail: {@code full} carries the summaries, {@code compact} drops them (large trees). */
    public static final String DETAIL_FULL = "full";
    public static final String DETAIL_COMPACT = "compact";
    /** Below this many files the default detail is full; above, compact. */
    static final int COMPACT_THRESHOLD = 300;
    static final int INVENTORY_SUMMARY_CHARS = 120;

    /** The user a call acts for: email for the access policy, Authentication for the service layer. */
    public record Caller(String email, Authentication authentication) {
    }

    /** What gets persisted: the model's request (re-validated on apply) and the view shown to the user. */
    record StoredPlan(ReorganizationPlanRequest request, ReorganizationPlanView view) {
    }

    // ── inventory ───────────────────────────────────────────────────────────

    /**
     * A compact, model-readable inventory of a folder subtree: the existing folders, then every
     * file with its id, path, extension, size, last modification and metadata keys. Capped at
     * {@code maxItems} entries and {@code maxDepth} levels; only documents the caller can read.
     */
    public String inventory(UUID rootId, Integer maxDepth, Integer maxItems, Caller caller) {
        return inventory(rootId, maxDepth, maxItems, null, caller);
    }

    /**
     * @param detail {@code full} (summaries included), {@code compact} (no summaries), or null to
     *               decide from the size: full under {@value #COMPACT_THRESHOLD} files
     */
    public String inventory(UUID rootId, Integer maxDepth, Integer maxItems, String detail, Caller caller) {
        int depthLimit = clamp(maxDepth, DEFAULT_MAX_DEPTH, MAX_DEPTH);
        int itemLimit = clamp(maxItems, DEFAULT_MAX_ITEMS, MAX_ITEMS);
        String detailKey = detail == null || detail.isBlank() ? "auto" : detail.trim().toLowerCase(Locale.ROOT);
        String cacheKey = ReorganizationInventoryCache.key(caller.email(), rootId, depthLimit, itemLimit, detailKey);
        String cached = inventoryCache.get(cacheKey);
        if (cached != null) {
            log.debug("[REORG] inventory served from cache for {} (root {})", caller.email(), rootId);
            return cached;
        }
        if (!inventoryCache.tryAcquire(caller.email())) {
            throw new IllegalStateException("Inventory rate limit reached (" + inventoryCache.rateLimit()
                    + " inventories per " + inventoryCache.rateWindow().toMinutes() + " minutes): reuse the "
                    + "inventory you already have, or narrow the folder and try again later.");
        }
        String inventory = buildInventory(rootId, depthLimit, itemLimit, detailKey, caller);
        inventoryCache.put(cacheKey, inventory);
        return inventory;
    }

    /** A file of the inventory with its absolute path, before enrichment. */
    private record InventoryFile(Document document, String path) {
    }

    private String buildInventory(UUID rootId, int depthLimit, int itemLimit, String detail, Caller caller) {
        Map<String, List<Document>> childrenCache = new HashMap<>();
        String rootPath = absolutePath(rootId, new HashMap<>(), caller);

        List<String> folders = new ArrayList<>();
        List<InventoryFile> files = new ArrayList<>();
        int listed = 0;
        boolean truncated = false;

        Deque<Object[]> queue = new ArrayDeque<>();
        queue.add(new Object[]{rootId, 0, rootPath});
        while (!queue.isEmpty()) {
            Object[] entry = queue.poll();
            UUID folderId = (UUID) entry[0];
            int depth = (Integer) entry[1];
            String path = (String) entry[2];
            for (Document child : children(folderId, childrenCache, caller)) {
                if (listed >= itemLimit) {
                    truncated = true;
                    break;
                }
                listed++;
                String childPath = join(path, child.getName());
                if (child.getType() == DocumentType.FOLDER) {
                    folders.add(childPath + "  (id " + child.getId() + ")");
                    if (depth + 1 < depthLimit) {
                        queue.add(new Object[]{child.getId(), depth + 1, childPath});
                    } else {
                        folders.set(folders.size() - 1, folders.getLast() + "  [not expanded: depth limit]");
                    }
                } else {
                    files.add(new InventoryFile(child, childPath));
                }
            }
            if (truncated) break;
        }

        // Two grouped queries for the whole inventory: the insights and the audit activity
        List<UUID> fileIds = files.stream().map(f -> f.document().getId()).filter(Objects::nonNull).toList();
        Map<UUID, org.openfilz.dms.dto.response.DocumentInsightView> insights = insightsOf(fileIds, caller);
        Map<UUID, org.openfilz.dms.dto.audit.DocumentActivity> activity = activityOf(fileIds, caller);
        boolean withSummaries = DETAIL_FULL.equals(detail)
                || (!DETAIL_COMPACT.equals(detail) && files.size() < COMPACT_THRESHOLD);

        StringBuilder sb = new StringBuilder();
        sb.append("Inventory of ").append(rootPath).append(rootId != null ? " (id " + rootId + ")" : " (root level)")
                .append(": ").append(folders.size()).append(" folder(s), ").append(files.size()).append(" file(s)");
        if (truncated) sb.append(" — TRUNCATED at ").append(itemLimit).append(" entries; narrow the folder or raise maxItems");
        sb.append(".\n");
        appendAggregates(sb, files, insights, activity);
        if (!folders.isEmpty()) {
            sb.append("\nExisting folders:\n");
            folders.forEach(f -> sb.append("  ").append(f).append('\n'));
        }
        if (files.isEmpty()) {
            sb.append("\nNo files.\n");
        } else {
            sb.append("\nFiles (id | path | ext | size | modified by | insights: cat / language / pages")
                    .append(withSummaries ? " / \"summary\"" : "")
                    .append(" | metadata | activity: last action, actions, users):\n");
            for (InventoryFile file : files) {
                sb.append("  ").append(fileRow(file, insights.get(file.document().getId()),
                        activity.get(file.document().getId()), withSummaries)).append('\n');
            }
        }
        return sb.toString();
    }

    private static String fileRow(InventoryFile file, org.openfilz.dms.dto.response.DocumentInsightView insight,
                                  org.openfilz.dms.dto.audit.DocumentActivity activity, boolean withSummary) {
        Document doc = file.document();
        StringBuilder row = new StringBuilder();
        row.append(doc.getId()).append(" | ").append(file.path()).append(" | ").append(extension(doc.getName()))
                .append(" | ").append(humanSize(doc.getSize()))
                .append(" | mod ").append(doc.getUpdatedAt() != null ? doc.getUpdatedAt().toLocalDate() : "?");
        if (doc.getCreatedBy() != null && !doc.getCreatedBy().isBlank()) {
            row.append(" by ").append(doc.getCreatedBy());
        }
        if (insight != null) {
            List<String> segments = new ArrayList<>();
            if (insight.category() != null) segments.add("cat " + insight.category());
            if (insight.language() != null) segments.add(insight.language());
            if (insight.pageCount() != null) segments.add(insight.pageCount() + " p");
            if (withSummary && insight.summary() != null) {
                String summary = insight.summary().replace('\n', ' ').replace('"', '\'');
                segments.add("\"" + (summary.length() > INVENTORY_SUMMARY_CHARS
                        ? summary.substring(0, INVENTORY_SUMMARY_CHARS - 1) + "…" : summary) + "\"");
            } else if (insight.keywords() != null && !insight.keywords().isEmpty()) {
                segments.add("kw " + String.join("/", insight.keywords().stream().limit(5).toList()));
            }
            if (!segments.isEmpty()) {
                row.append(" | ").append(String.join(" | ", segments));
            }
        }
        row.append(metadataSummary(doc));
        if (activity != null) {
            row.append(" | last ").append(activity.lastAt() != null ? activity.lastAt().toLocalDate() : "?")
                    .append(", ").append(activity.actions()).append(activity.actions() == 1 ? " action" : " actions")
                    .append(", ").append(activity.actors()).append(activity.actors() == 1 ? " user" : " users");
        }
        return row.toString();
    }

    /** Two lines that let the model choose "by category" or "active / archive" before reading rows. */
    private static void appendAggregates(StringBuilder sb, List<InventoryFile> files,
                                         Map<UUID, org.openfilz.dms.dto.response.DocumentInsightView> insights,
                                         Map<UUID, org.openfilz.dms.dto.audit.DocumentActivity> activity) {
        if (files.isEmpty()) {
            return;
        }
        Map<String, Integer> categories = new java.util.TreeMap<>();
        int withInsights = 0;
        for (InventoryFile file : files) {
            org.openfilz.dms.dto.response.DocumentInsightView insight = insights.get(file.document().getId());
            if (insight != null && insight.category() != null) {
                withInsights++;
                categories.merge(insight.category(), 1, Integer::sum);
            }
        }
        if (withInsights > 0) {
            sb.append("Categories present (").append(withInsights).append(" of ").append(files.size())
                    .append(" files have insights): ")
                    .append(categories.entrySet().stream()
                            .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                            .map(e -> e.getKey() + " " + e.getValue())
                            .collect(Collectors.joining(", ")))
                    .append(".\n");
        } else {
            sb.append("No AI insights on these files yet: judge them by name, path, dates and activity, "
                    + "and read a file only when its nature is unclear.\n");
        }
        OffsetDateTime yearAgo = OffsetDateTime.now().minusMonths(12);
        long untouched = files.stream().filter(file -> {
            org.openfilz.dms.dto.audit.DocumentActivity a = activity.get(file.document().getId());
            OffsetDateTime last = a != null && a.lastAt() != null ? a.lastAt() : file.document().getUpdatedAt();
            return last != null && last.isBefore(yearAgo);
        }).count();
        if (untouched > 0) {
            sb.append("Activity: ").append(untouched).append(" file(s) untouched for more than 12 months.\n");
        }
    }

    private Map<UUID, org.openfilz.dms.dto.response.DocumentInsightView> insightsOf(List<UUID> ids, Caller caller) {
        if (ids.isEmpty() || insightStore == null) {
            return Map.of();
        }
        try {
            List<org.openfilz.dms.entity.AiDocumentInsight> rows = blockWithAuth(insightStore.findAll(ids).collectList(), caller);
            Map<UUID, org.openfilz.dms.dto.response.DocumentInsightView> out = new HashMap<>();
            if (rows != null) {
                rows.forEach(row -> out.put(row.getDocumentId(), org.openfilz.dms.service.insight.DocumentInsightStore.toView(row)));
            }
            return out;
        } catch (Exception e) {
            log.debug("[REORG] insights lookup failed: {}", e.getMessage());
            return Map.of();
        }
    }

    private Map<UUID, org.openfilz.dms.dto.audit.DocumentActivity> activityOf(List<UUID> ids, Caller caller) {
        if (ids.isEmpty() || auditDAO == null) {
            return Map.of();
        }
        try {
            List<org.openfilz.dms.dto.audit.DocumentActivity> rows = blockWithAuth(auditDAO.activitySummary(ids).collectList(), caller);
            Map<UUID, org.openfilz.dms.dto.audit.DocumentActivity> out = new HashMap<>();
            if (rows != null) {
                rows.forEach(row -> out.put(row.documentId(), row));
            }
            return out;
        } catch (Exception e) {
            log.debug("[REORG] activity lookup failed: {}", e.getMessage());
            return Map.of();
        }
    }

    // ── validation ──────────────────────────────────────────────────────────

    /** Validate a proposal against the live state without persisting or changing anything. */
    public ReorganizationPlanView validate(ReorganizationPlanRequest request, Caller caller) {
        if (request == null || request.moves() == null || request.moves().isEmpty()) {
            throw new IllegalArgumentException("The plan has no moves.");
        }
        UUID rootId = resolveRoot(request.rootFolder(), caller);
        Map<UUID, Document> docCache = new HashMap<>();
        Map<String, List<Document>> childrenCache = new HashMap<>();
        String rootPath = absolutePath(rootId, docCache, caller);
        Map<String, TargetResolution> targets = new LinkedHashMap<>();
        Set<UUID> seen = new HashSet<>();
        Map<String, UUID> plannedNames = new HashMap<>();
        Set<String> foldersToCreate = new LinkedHashSet<>();
        List<Item> items = new ArrayList<>();
        // Modify permission per document/folder, asked once for the whole plan (see prefetchModifiable)
        Map<UUID, Boolean> modifiable = new HashMap<>();

        if (request.createFolders() != null) {
            for (String path : request.createFolders()) {
                List<String> segments = normalizePath(path);
                if (segments == null || segments.isEmpty()) continue;
                TargetResolution target = resolveTarget(rootId, rootPath, segments, targets, childrenCache, docCache, caller);
                if (!target.exists()) {
                    if (canCreateIn(target.deepestExistingId(), caller, modifiable)) {
                        foldersToCreate.addAll(target.missingPaths());
                    } else {
                        log.debug("[REORG] createFolders '{}' skipped: no permission under {}", path, target.deepestExistingPath());
                    }
                }
            }
        }

        // Resolve every document first, so the modify permission is asked once for the whole plan
        List<Document> resolved = new ArrayList<>(request.moves().size());
        for (ReorganizationPlanRequest.Move move : request.moves()) {
            resolved.add(resolveDocument(move.document(), caller, docCache));
        }
        prefetchModifiable(resolved, caller, modifiable);

        for (int index = 0; index < request.moves().size(); index++) {
            ReorganizationPlanRequest.Move move = request.moves().get(index);
            Document doc = resolved.get(index);
            if (doc == null) {
                items.add(new Item(null, move.document(), null, null, null, false, false,
                        "No document '" + move.document() + "' is visible to you (use its id from the inventory)."));
                continue;
            }
            String currentPath = absolutePath(doc.getParentId(), docCache, caller);
            String type = doc.getType().name();
            if (!seen.add(doc.getId())) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, null, false, false,
                        "Listed more than once in the plan."));
                continue;
            }
            List<String> segments = normalizePath(move.target());
            if (segments == null) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, null, false, false,
                        "Invalid target path '" + move.target() + "'."));
                continue;
            }
            TargetResolution target = resolveTarget(rootId, rootPath, segments, targets, childrenCache, docCache, caller);
            String targetPath = target.path();

            if (!canModify(doc.getId(), caller, modifiable)) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, target.exists(), false,
                        "You don't have permission to move this document."));
                continue;
            }
            if (doc.getType() == DocumentType.FOLDER && target.existingChain().contains(doc.getId())) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, target.exists(), false,
                        "A folder cannot be moved into itself or one of its subfolders."));
                continue;
            }
            if (target.exists() && sameFolder(doc.getParentId(), target.folderId())) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, true, false,
                        "Already in the target folder."));
                continue;
            }
            if (target.exists() && !canCreateIn(target.folderId(), caller, modifiable)) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, true, false,
                        "You don't have permission to add documents to " + targetPath + "."));
                continue;
            }
            if (!target.exists() && !canCreateIn(target.deepestExistingId(), caller, modifiable)) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, false, false,
                        "You don't have permission to create folders in " + target.deepestExistingPath() + "."));
                continue;
            }
            if (target.exists() && nameTaken(doc.getName(), target.folderId(), caller)) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, true, false,
                        "A document named '" + doc.getName() + "' already exists in " + targetPath + "."));
                continue;
            }
            String nameKey = targetPath + " " + doc.getName();
            UUID previous = plannedNames.putIfAbsent(nameKey, doc.getId());
            if (previous != null) {
                items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, target.exists(), false,
                        "Another item of this plan with the same name goes to " + targetPath + "."));
                continue;
            }
            if (!target.exists()) {
                foldersToCreate.addAll(target.missingPaths());
            }
            items.add(new Item(doc.getId(), doc.getName(), type, currentPath, targetPath, target.exists(), true, null));
        }

        int applicable = (int) items.stream().filter(Item::applicable).count();
        List<String> creates = foldersToCreate.stream().sorted(Comparator.comparingInt(ReorganizationPlanService::depth)
                .thenComparing(s -> s)).toList();
        return new ReorganizationPlanView(null, null, rootId, rootPath, blankToNull(request.rationale()), items,
                creates, applicable, items.size() - applicable, caller.email(), null, null, null);
    }

    // ── lifecycle ───────────────────────────────────────────────────────────

    /**
     * Validate and persist a proposal for the user's confirmation. Nothing is persisted when no
     * item is applicable — the returned view (id null) still carries every issue for the model.
     */
    public ReorganizationPlanView propose(ReorganizationPlanRequest request, UUID conversationId, Caller caller) {
        ReorganizationPlanView view = validate(request, caller);
        if (view.applicable() == 0) {
            return view;
        }
        OffsetDateTime now = OffsetDateTime.now();
        AiReorganizationPlan entity = AiReorganizationPlan.builder()
                .createdBy(caller.email())
                .conversationId(conversationId)
                .rootFolderId(view.rootFolderId())
                .status(STATUS_PROPOSED)
                .createdAt(now)
                .build();
        ReorganizationPlanView draft = view.withPersistence(null, STATUS_PROPOSED, caller.email(), now, null, null);
        entity.setPlan(Json.of(JSON.writeValueAsString(new StoredPlan(request, draft))));
        AiReorganizationPlan saved = planRepository.save(entity).block();
        if (saved == null) {
            throw new IllegalStateException("The plan could not be saved.");
        }
        log.info("[REORG] Plan {} proposed by {}: {} applicable, {} blocked, {} folder(s) to create",
                saved.getId(), caller.email(), view.applicable(), view.blocked(), view.foldersToCreate().size());
        return draft.withPersistence(saved.getId(), STATUS_PROPOSED, caller.email(), now, null, null);
    }

    /** A plan of the caller's; someone else's plan answers 404 so its existence is not revealed. */
    public ReorganizationPlanView get(UUID planId, Caller caller) {
        return viewOf(loadOwned(planId, caller));
    }

    /**
     * Apply the selected items of a proposed plan: create the missing target folders, then move
     * each item, one by one so one failure never undoes the others. The plan is re-validated
     * first — the library may have changed since it was proposed.
     *
     * @param itemIds document ids to apply; null or empty = every applicable item
     */
    public ReorganizationApplyResult apply(UUID planId, List<UUID> itemIds, Caller caller) {
        if (rolePolicy != null && !rolePolicy.isAllowed(caller.authentication(), ToolCapability.DOCUMENT_WRITE)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your OpenFilz role does not allow moving documents (" + ToolCapability.DOCUMENT_WRITE + ").");
        }
        AiReorganizationPlan entity = loadOwned(planId, caller);
        if (!STATUS_PROPOSED.equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This plan is " + entity.getStatus().toLowerCase(Locale.ROOT).replace('_', ' ') + " and cannot be applied again.");
        }
        StoredPlan stored = storedPlan(entity);
        ReorganizationPlanView fresh = validate(stored.request(), caller);
        Set<UUID> selected = itemIds == null || itemIds.isEmpty() ? null : new HashSet<>(itemIds);

        List<Item> toApply = fresh.items().stream()
                .filter(Item::applicable)
                .filter(item -> selected == null || selected.contains(item.documentId()))
                .toList();
        if (toApply.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No applicable item was selected.");
        }

        Map<UUID, Document> docCache = new HashMap<>();
        Map<String, List<Document>> childrenCache = new HashMap<>();
        Set<String> modifiedFolders = new LinkedHashSet<>();
        List<String> createdFolders = new ArrayList<>();
        Map<String, UUID> folderIdsByPath = new HashMap<>();
        folderIdsByPath.put(fresh.rootFolderPath(), fresh.rootFolderId());

        // Folders first, shallowest first, so every parent exists before its children
        Set<String> neededPaths = new LinkedHashSet<>();
        toApply.stream().filter(item -> !item.targetExists()).forEach(item -> neededPaths.add(item.targetPath()));
        if (stored.request().createFolders() != null) {
            fresh.foldersToCreate().forEach(neededPaths::add);
        }
        Map<UUID, String> folderFailures = new HashMap<>();
        List<String> orderedPaths = neededPaths.stream()
                .sorted(Comparator.comparingInt(ReorganizationPlanService::depth).thenComparing(s -> s)).toList();
        Map<String, String> pathFailures = new HashMap<>();
        for (String path : orderedPaths) {
            try {
                ensureFolder(path, fresh.rootFolderPath(), fresh.rootFolderId(), folderIdsByPath, childrenCache,
                        docCache, createdFolders, modifiedFolders, caller);
            } catch (Exception e) {
                log.warn("[REORG] Plan {}: could not create folder {}: {}", planId, path, e.toString());
                pathFailures.put(path, e.getMessage());
            }
        }

        List<ItemResult> results = new ArrayList<>();
        int moved = 0;
        int failed = 0;
        // Identity, not document id: a document listed twice has one applicable item and one
        // blocked duplicate, and only the applicable one must be attempted
        Set<Item> applied = new HashSet<>(toApply);
        for (Item item : fresh.items()) {
            if (item.documentId() == null || !applied.contains(item)) {
                results.add(new ItemResult(item.documentId(), "SKIPPED",
                        item.applicable() ? "Not selected." : item.issue()));
                continue;
            }
            String pathFailure = pathFailures.get(item.targetPath());
            if (pathFailure == null) {
                pathFailure = pathFailures.entrySet().stream()
                        .filter(e -> item.targetPath().startsWith(e.getKey() + "/"))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
            if (pathFailure != null) {
                failed++;
                results.add(new ItemResult(item.documentId(), "FAILED", "Target folder could not be created: " + pathFailure));
                continue;
            }
            UUID targetId = folderIdsByPath.get(item.targetPath());
            if (targetId == null && !fresh.rootFolderPath().equals(item.targetPath())) {
                targetId = resolveExistingPath(item.targetPath(), fresh.rootFolderPath(), fresh.rootFolderId(),
                        folderIdsByPath, childrenCache, docCache, caller);
            }
            if (targetId == null && !fresh.rootFolderPath().equals(item.targetPath())) {
                failed++;
                results.add(new ItemResult(item.documentId(), "FAILED", "Target folder " + item.targetPath() + " not found."));
                continue;
            }
            Document doc = blockWithAuth(documentRepository.findByIdAndActive(item.documentId(), true), caller);
            UUID sourceParent = doc != null ? doc.getParentId() : null;
            try {
                MoveRequest moveRequest = new MoveRequest(List.of(item.documentId()), targetId, false);
                if ("FOLDER".equals(item.type())) {
                    blockWithAuth(documentService.moveFolders(moveRequest), caller);
                } else {
                    blockWithAuth(documentService.moveFiles(moveRequest), caller);
                }
                moved++;
                modifiedFolders.add(AiToolTurnEffects.folderKey(sourceParent));
                modifiedFolders.add(AiToolTurnEffects.folderKey(targetId));
                results.add(new ItemResult(item.documentId(), "MOVED", null));
            } catch (Exception e) {
                failed++;
                log.warn("[REORG] Plan {}: could not move {} to {}: {}", planId, item.name(), item.targetPath(), e.toString());
                results.add(new ItemResult(item.documentId(), "FAILED", reason(e)));
            }
        }

        String status = moved == 0 ? STATUS_FAILED : failed == 0 ? STATUS_APPLIED : STATUS_PARTIALLY_APPLIED;
        OffsetDateTime now = OffsetDateTime.now();
        ReorganizationPlanView applied1 = fresh.withPersistence(entity.getId(), status, entity.getCreatedBy(),
                entity.getCreatedAt(), now, results);
        entity.setStatus(status);
        entity.setAppliedAt(now);
        entity.setPlan(Json.of(JSON.writeValueAsString(new StoredPlan(stored.request(), applied1))));
        entity.setResult(Json.of(JSON.writeValueAsString(results)));
        planRepository.save(entity).block();
        // The library changed: the next inventory must be rebuilt, not served from the cache
        inventoryCache.invalidate(caller.email());
        log.info("[REORG] Plan {} applied by {}: {} moved, {} failed, {} folder(s) created → {}",
                planId, caller.email(), moved, failed, createdFolders.size(), status);
        int skipped = fresh.items().size() - moved - failed;
        return new ReorganizationApplyResult(entity.getId(), status, moved, failed, skipped, createdFolders,
                List.copyOf(modifiedFolders), applied1);
    }

    /** Mark a proposed plan as discarded (idempotent for already-discarded plans). */
    public ReorganizationPlanView discard(UUID planId, Caller caller) {
        AiReorganizationPlan entity = loadOwned(planId, caller);
        if (STATUS_DISCARDED.equals(entity.getStatus())) {
            return viewOf(entity);
        }
        if (!STATUS_PROPOSED.equals(entity.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a proposed plan can be discarded.");
        }
        StoredPlan stored = storedPlan(entity);
        ReorganizationPlanView view = stored.view().withPersistence(entity.getId(), STATUS_DISCARDED,
                entity.getCreatedBy(), entity.getCreatedAt(), null, null);
        entity.setStatus(STATUS_DISCARDED);
        entity.setPlan(Json.of(JSON.writeValueAsString(new StoredPlan(stored.request(), view))));
        planRepository.save(entity).block();
        return view;
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private record TargetResolution(String path, boolean exists, UUID folderId, List<UUID> existingChain,
                                    UUID deepestExistingId, String deepestExistingPath, List<String> missingPaths) {
    }

    /**
     * Walk a relative path from the root folder, matching existing sub-folders by name; the
     * first missing segment and everything below it are reported as folders to create.
     */
    private TargetResolution resolveTarget(UUID rootId, String rootPath, List<String> segments,
                                           Map<String, TargetResolution> cache, Map<String, List<Document>> childrenCache,
                                           Map<UUID, Document> docCache, Caller caller) {
        String key = String.join("/", segments);
        TargetResolution cached = cache.get(key);
        if (cached != null) return cached;

        UUID current = rootId;
        String currentPath = rootPath;
        List<UUID> chain = new ArrayList<>();
        if (rootId != null) chain.add(rootId);
        boolean exists = true;
        UUID deepestExisting = rootId;
        String deepestExistingPath = rootPath;
        List<String> missing = new ArrayList<>();
        for (String segment : segments) {
            currentPath = join(currentPath, segment);
            if (exists) {
                Document child = children(current, childrenCache, caller).stream()
                        .filter(d -> d.getType() == DocumentType.FOLDER && d.getName().equals(segment))
                        .findFirst().orElse(null);
                if (child == null) {
                    child = children(current, childrenCache, caller).stream()
                            .filter(d -> d.getType() == DocumentType.FOLDER && d.getName().equalsIgnoreCase(segment))
                            .findFirst().orElse(null);
                }
                if (child != null) {
                    current = child.getId();
                    chain.add(current);
                    deepestExisting = current;
                    deepestExistingPath = currentPath;
                    docCache.putIfAbsent(child.getId(), child);
                    continue;
                }
                exists = false;
            }
            missing.add(currentPath);
        }
        TargetResolution resolution = new TargetResolution(currentPath, exists, exists ? current : null, chain,
                deepestExisting, deepestExistingPath, missing);
        cache.put(key, resolution);
        return resolution;
    }

    private void ensureFolder(String path, String rootPath, UUID rootId, Map<String, UUID> folderIdsByPath,
                              Map<String, List<Document>> childrenCache, Map<UUID, Document> docCache,
                              List<String> createdFolders, Set<String> modifiedFolders, Caller caller) {
        if (folderIdsByPath.containsKey(path)) return;
        String parentPath = parentOf(path);
        if (parentPath == null) {
            return; // the root itself
        }
        if (!folderIdsByPath.containsKey(parentPath)) {
            ensureFolder(parentPath, rootPath, rootId, folderIdsByPath, childrenCache, docCache, createdFolders,
                    modifiedFolders, caller);
        }
        UUID parentId = folderIdsByPath.get(parentPath);
        if (parentId == null && !rootPath.equals(parentPath)) {
            parentId = resolveExistingPath(parentPath, rootPath, rootId, folderIdsByPath, childrenCache, docCache, caller);
            if (parentId == null) {
                throw new IllegalStateException("Parent folder " + parentPath + " does not exist.");
            }
        }
        String name = path.substring(path.lastIndexOf('/') + 1);
        Document existing = children(parentId, childrenCache, caller).stream()
                .filter(d -> d.getType() == DocumentType.FOLDER && d.getName().equals(name))
                .findFirst().orElse(null);
        if (existing != null) {
            folderIdsByPath.put(path, existing.getId());
            return;
        }
        FolderResponse created = blockWithAuth(documentService.createFolder(new CreateFolderRequest(name, parentId)), caller);
        if (created == null) {
            throw new IllegalStateException("Folder " + path + " could not be created.");
        }
        folderIdsByPath.put(path, created.id());
        childrenCache.remove(AiToolTurnEffects.folderKey(parentId));
        createdFolders.add(path);
        modifiedFolders.add(AiToolTurnEffects.folderKey(parentId));
    }

    /** Id of an existing absolute path under the root, walking down from the root by folder names. */
    private UUID resolveExistingPath(String path, String rootPath, UUID rootId, Map<String, UUID> folderIdsByPath,
                                     Map<String, List<Document>> childrenCache, Map<UUID, Document> docCache,
                                     Caller caller) {
        if (rootPath.equals(path)) return rootId;
        String relative = rootPath.equals("/") ? path.substring(1) : path.substring(rootPath.length() + 1);
        List<String> segments = normalizePath(relative);
        if (segments == null) return null;
        TargetResolution resolution = resolveTarget(rootId, rootPath, segments, new HashMap<>(), childrenCache, docCache, caller);
        if (resolution.exists()) {
            folderIdsByPath.put(path, resolution.folderId());
            return resolution.folderId();
        }
        return null;
    }

    private UUID resolveRoot(String rootFolder, Caller caller) {
        if (rootFolder == null || rootFolder.isBlank() || "null".equalsIgnoreCase(rootFolder.trim())
                || "root".equalsIgnoreCase(rootFolder.trim()) || "/".equals(rootFolder.trim())) {
            return null;
        }
        Document folder = resolveDocument(rootFolder, caller, new HashMap<>());
        if (folder == null || folder.getType() != DocumentType.FOLDER) {
            throw new IllegalArgumentException("No folder '" + rootFolder + "' is visible to you.");
        }
        return folder.getId();
    }

    /** Resolve an id or an exact (case-insensitive) unique name to a document the caller can read. */
    Document resolveDocument(String ref, Caller caller, Map<UUID, Document> docCache) {
        if (ref == null || ref.isBlank()) return null;
        String trimmed = ref.trim();
        UUID id = parseUuid(trimmed);
        if (id != null) {
            Document doc = docCache.containsKey(id) ? docCache.get(id)
                    : blockWithAuth(documentRepository.findByIdAndActive(id, true), caller);
            if (doc == null || !canRead(doc.getId(), caller)) return null;
            docCache.put(id, doc);
            return doc;
        }
        List<Document> found = blockWithAuth(documentRepository.findByNameIgnoreCaseAndActiveTrue(trimmed).collectList(), caller);
        if (found == null) return null;
        List<Document> exact = found.stream()
                .filter(d -> d.getName().equalsIgnoreCase(trimmed) && canRead(d.getId(), caller))
                .toList();
        if (exact.size() != 1) return null;
        docCache.put(exact.getFirst().getId(), exact.getFirst());
        return exact.getFirst();
    }

    /** Readable, active children of a folder (null = root level), folders first then by name. */
    private List<Document> children(UUID folderId, Map<String, List<Document>> cache, Caller caller) {
        return cache.computeIfAbsent(AiToolTurnEffects.folderKey(folderId), key -> {
            List<Document> all = blockWithAuth((folderId == null
                    ? documentRepository.findByParentIdIsNullAndActiveIsTrue()
                    : documentRepository.findByParentIdAndActiveIsTrue(folderId)).collectList(), caller);
            if (all == null) return List.of();
            Set<UUID> readable = readableOf(all, caller);
            return all.stream()
                    .filter(d -> readable.contains(d.getId()))
                    .sorted(Comparator.comparing((Document d) -> d.getType() != DocumentType.FOLDER)
                            .thenComparing(d -> d.getName().toLowerCase(Locale.ROOT)))
                    .toList();
        });
    }

    /** Absolute path of a folder ({@code "/"} for the root level), walking up the parents. */
    String absolutePath(UUID folderId, Map<UUID, Document> docCache, Caller caller) {
        if (folderId == null) return "/";
        Deque<String> names = new ArrayDeque<>();
        UUID current = folderId;
        int guard = 0;
        while (current != null && guard++ < 64) {
            UUID id = current;
            Document doc = docCache.computeIfAbsent(id, k -> blockWithAuth(documentRepository.findById(k), caller));
            if (doc == null) break;
            names.addFirst(doc.getName());
            current = doc.getParentId();
        }
        return "/" + String.join("/", names);
    }

    private boolean nameTaken(String name, UUID folderId, Caller caller) {
        Boolean taken = blockWithAuth(folderId == null
                ? documentRepository.existsByNameAndParentIdIsNullAndActiveIsTrue(name)
                : documentRepository.existsByNameAndParentIdAndActiveIsTrue(name, folderId), caller);
        return Boolean.TRUE.equals(taken);
    }

    private AiReorganizationPlan loadOwned(UUID planId, Caller caller) {
        AiReorganizationPlan entity = planId == null ? null : planRepository.findById(planId).block();
        if (entity == null || caller.email() == null || !caller.email().equalsIgnoreCase(entity.getCreatedBy())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plan not found");
        }
        return entity;
    }

    private ReorganizationPlanView viewOf(AiReorganizationPlan entity) {
        StoredPlan stored = storedPlan(entity);
        List<ItemResult> results = entity.getResult() == null ? null
                : JSON.readValue(entity.getResult().asString(), JSON.getTypeFactory().constructCollectionType(List.class, ItemResult.class));
        return stored.view().withPersistence(entity.getId(), entity.getStatus(), entity.getCreatedBy(),
                entity.getCreatedAt(), entity.getAppliedAt(), results);
    }

    private static StoredPlan storedPlan(AiReorganizationPlan entity) {
        return JSON.readValue(entity.getPlan().asString(), StoredPlan.class);
    }

    private boolean canRead(UUID documentId, Caller caller) {
        return documentId == null || Boolean.TRUE.equals(accessPolicy.canRead(documentId, caller.email()).block());
    }

    private boolean canModify(UUID documentId, Caller caller) {
        return Boolean.TRUE.equals(accessPolicy.canModify(documentId, caller.email()).block());
    }

    /** Ids of the documents the caller may read: one policy call for the whole list. */
    private Set<UUID> readableOf(List<Document> documents, Caller caller) {
        Set<UUID> ids = documents.stream().map(Document::getId).filter(Objects::nonNull)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (accessPolicy.permitAll()) {
            return ids;
        }
        Set<UUID> readable = blockWithAuth(accessPolicy.readable(ids, caller.email()), caller);
        return readable == null ? Set.of() : readable;
    }

    /** Ask the modify permission once for every resolved document not decided yet. */
    private void prefetchModifiable(List<Document> documents, Caller caller, Map<UUID, Boolean> modifiable) {
        Set<UUID> ids = documents.stream().filter(Objects::nonNull).map(Document::getId)
                .filter(id -> id != null && !modifiable.containsKey(id))
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (ids.isEmpty()) {
            return;
        }
        Set<UUID> allowed;
        if (accessPolicy.permitAll()) {
            allowed = ids;
        } else {
            Set<UUID> result = blockWithAuth(accessPolicy.modifiable(ids, caller.email()), caller);
            allowed = result == null ? Set.of() : result;
        }
        for (UUID id : ids) {
            modifiable.put(id, allowed.contains(id));
        }
    }

    /** Memoised per validation: a target folder not covered by the prefetch is asked once. */
    private boolean canModify(UUID documentId, Caller caller, Map<UUID, Boolean> modifiable) {
        return modifiable.computeIfAbsent(documentId, id -> canModify(id, caller));
    }

    private boolean canCreateIn(UUID folderId, Caller caller, Map<UUID, Boolean> modifiable) {
        return folderId == null
                ? Boolean.TRUE.equals(accessPolicy.canCreateAtRoot(caller.email()).block())
                : canModify(folderId, caller, modifiable);
    }

    private static <T> T blockWithAuth(Mono<T> mono, Caller caller) {
        return (caller.authentication() != null
                ? mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller.authentication()))
                : mono).block();
    }

    /**
     * Split a relative folder path into segments: {@code "Finance / Invoices/"} → {@code [Finance, Invoices]};
     * empty, {@code "/"} or {@code "."} → the root folder itself (empty list); {@code ".."} or an
     * over-long segment → invalid (null).
     */
    static List<String> normalizePath(String path) {
        if (path == null) return List.of();
        List<String> segments = new ArrayList<>();
        for (String raw : path.replace('\\', '/').split("/")) {
            String segment = raw.trim();
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment) || segment.length() > 255) return null;
            segments.add(segment);
        }
        return segments;
    }

    static String join(String parentPath, String name) {
        return "/".equals(parentPath) ? "/" + name : parentPath + "/" + name;
    }

    private static String parentOf(String path) {
        int idx = path.lastIndexOf('/');
        if (idx <= 0) return idx == 0 && path.length() > 1 ? "/" : null;
        return path.substring(0, idx);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(c -> c == '/').count();
    }

    private static boolean sameFolder(UUID a, UUID b) {
        return a == null ? b == null : a.equals(b);
    }

    private static int clamp(Integer value, int defaultValue, int max) {
        if (value == null || value <= 0) return defaultValue;
        return Math.min(value, max);
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String extension(String name) {
        if (name == null) return "";
        int idx = name.lastIndexOf('.');
        return idx > 0 && idx < name.length() - 1 ? name.substring(idx + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String humanSize(Long size) {
        if (size == null) return "?";
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return (size / 1024) + " KB";
        return String.format(Locale.ROOT, "%.1f MB", size / (1024.0 * 1024.0));
    }

    private static String metadataSummary(Document doc) {
        if (doc.getMetadata() == null) return "";
        try {
            Map<?, ?> metadata = JSON.readValue(doc.getMetadata().asString(), Map.class);
            if (metadata == null || metadata.isEmpty()) return "";
            String summary = metadata.entrySet().stream().limit(6)
                    .map(e -> e.getKey() + "=" + String.valueOf(e.getValue()).replace('\n', ' '))
                    .map(s -> s.length() > 60 ? s.substring(0, 57) + "…" : s)
                    .collect(Collectors.joining(", "));
            return " | " + summary;
        } catch (Exception e) {
            return "";
        }
    }

    private static String reason(Exception e) {
        if (e instanceof ResponseStatusException rse && rse.getReason() != null) return rse.getReason();
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
