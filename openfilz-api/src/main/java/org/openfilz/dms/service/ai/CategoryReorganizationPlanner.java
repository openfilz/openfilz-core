package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest;
import org.openfilz.dms.dto.request.ReorganizationPlanRequest.Move;
import org.openfilz.dms.dto.response.ReorganizationPlanView;
import org.openfilz.dms.entity.AiDocumentInsight;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.openfilz.dms.service.filing.CategoryFolderNames;
import org.openfilz.dms.service.insight.DocumentInsightStore;
import org.openfilz.dms.service.insight.InsightResult;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Reorganisation by kind, without a model: every folder of a scope whose files are of several
 * kinds (the tier-2 categories, from the model or the prototype classifier) gets one sub-folder
 * per kind, named in the language of the library's folder names — {@code Invoices} /
 * {@code Factures} — or an existing child that already denotes the kind, and the files move
 * there. Deterministic, seconds for thousands of files, and the result is an ordinary
 * {@link ReorganizationPlanView}: proposed, reviewed, applied and undone like a model's plan.
 * <p>
 * What is left alone: a folder whose dominant kind holds {@code split-min-purity} of its
 * categorised files (a home already), kinds with fewer than {@code split-min-group} files,
 * folders with fewer than {@code split-min-files} categorised files, files of kind
 * {@code other} or without a category. The scope root itself is treated like any folder: loose
 * files of one kind at the root get their folder.
 */
@Slf4j
@Service
@Lazy
public class CategoryReorganizationPlanner {

    static final int MAX_DEPTH = 6;
    static final int MAX_FILES = 5_000;

    private final DocumentRepository documentRepository;
    private final DocumentInsightStore insightStore;
    private final ReorganizationPlanService planService;
    private final AiProperties aiProperties;
    private final CategoryFolderNames folderNames;

    public CategoryReorganizationPlanner(DocumentRepository documentRepository, DocumentInsightStore insightStore,
                                         ReorganizationPlanService planService, AiProperties aiProperties) {
        this.documentRepository = documentRepository;
        this.insightStore = insightStore;
        this.planService = planService;
        this.aiProperties = aiProperties;
        this.folderNames = new CategoryFolderNames(aiProperties.getAutoFile().getFolderNames());
    }

    /** One folder of the scope: its path relative to the scope root ("" for the root itself), its files and sub-folders. */
    record ScopeFolder(UUID id, String relativePath, List<Document> files, List<Document> folders) {
    }

    /** What the planner found: the request to propose (null when nothing to do) and a human summary. */
    public record Draft(ReorganizationPlanRequest request, int mixedFolders, int moves, List<String> newFolders, String language) {
        public boolean isEmpty() {
            return request == null || request.moves().isEmpty();
        }
    }

    /** Propose the by-kind split of a scope as a stored plan; a view with no id when nothing needs splitting. */
    public ReorganizationPlanView propose(UUID rootFolderId, UUID conversationId, Caller caller) {
        Draft draft = draft(rootFolderId, caller);
        if (draft.isEmpty()) {
            return new ReorganizationPlanView(null, ReorganizationPlanService.STATUS_PROPOSED, rootFolderId,
                    planService.pathOf(rootFolderId, caller), "Every folder of this scope already holds documents of one kind.",
                    List.of(), List.of(), 0, 0, caller.email(), null, null, List.of());
        }
        ReorganizationPlanView view = planService.propose(draft.request(), conversationId, caller);
        log.info("[REORG] by-kind plan for {}: {} mixed folder(s), {} move(s), {} new folder(s) in '{}' -> {}",
                rootFolderId, draft.mixedFolders(), draft.moves(), draft.newFolders().size(), draft.language(), view.id());
        return view;
    }

    /** The plan as a request, computed and not stored. */
    public Draft draft(UUID rootFolderId, Caller caller) {
        AiProperties.Reorganization config = aiProperties.getReorganization();
        List<ScopeFolder> scope = walk(rootFolderId, caller);
        List<String> allFolderNames = new ArrayList<>();
        scope.forEach(f -> f.folders().forEach(d -> allFolderNames.add(d.getName())));
        String language = folderNames.languageOf(allFolderNames)
                .orElse(defaultLanguage());

        List<Move> moves = new ArrayList<>();
        List<String> created = new ArrayList<>();
        int mixed = 0;
        Map<UUID, String> categories = categoriesOf(scope.stream().flatMap(f -> f.files().stream()).map(Document::getId).toList(), caller);
        for (ScopeFolder folder : scope) {
            Map<String, List<Document>> byKind = new LinkedHashMap<>();
            for (Document file : folder.files()) {
                String kind = categories.get(file.getId());
                if (kind == null || InsightResult.OTHER.equals(kind)) continue;
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(file);
            }
            int categorised = byKind.values().stream().mapToInt(List::size).sum();
            if (categorised < Math.max(1, config.getSplitMinFiles())) continue;
            int dominant = byKind.values().stream().mapToInt(List::size).max().orElse(0);
            if (dominant >= config.getSplitMinPurity() * categorised && byKind.size() > 1) {
                // A home already: the odd files out are not worth a folder each
                continue;
            }
            List<String> kinds = byKind.entrySet().stream()
                    .filter(e -> e.getValue().size() >= Math.max(1, config.getSplitMinGroup()))
                    .map(Map.Entry::getKey).toList();
            if (kinds.isEmpty() || (kinds.size() == 1 && byKind.size() == 1 && !folder.relativePath().isEmpty())) {
                // One kind only, in its own folder: nothing to split
                continue;
            }
            mixed++;
            for (String kind : kinds) {
                Optional<String> target = targetFor(folder, kind, language);
                if (target.isEmpty()) continue;
                String path = folder.relativePath().isEmpty() ? target.get() : folder.relativePath() + "/" + target.get();
                boolean exists = folder.folders().stream().anyMatch(d -> d.getName().equals(target.get()));
                if (!exists && !created.contains(path)) created.add(path);
                for (Document file : byKind.get(kind)) {
                    moves.add(new Move(file.getId().toString(), path));
                }
            }
        }
        if (moves.isEmpty()) {
            return new Draft(null, 0, 0, List.of(), language);
        }
        String rationale = "Split " + mixed + " folder" + (mixed == 1 ? "" : "s") + " holding documents of several kinds into one "
                + "sub-folder per kind, named in " + language + " like the existing folders: " + String.join(", ", created.isEmpty()
                ? List.of("existing folders reused") : created) + ".";
        ReorganizationPlanRequest request = new ReorganizationPlanRequest(
                rootFolderId == null ? null : rootFolderId.toString(), moves, created, rationale);
        return new Draft(request, mixed, moves.size(), created, language);
    }

    /** An existing child folder denoting the kind (any language) wins over a new one named in the library's language. */
    private Optional<String> targetFor(ScopeFolder folder, String kind, String language) {
        for (Document child : folder.folders()) {
            if (folderNames.categoryOf(child.getName()).filter(kind::equalsIgnoreCase).isPresent()) {
                return Optional.of(child.getName());
            }
        }
        return folderNames.nameOf(kind, language);
    }

    private String defaultLanguage() {
        String configured = aiProperties.getAutoFile().getDefaultLanguage();
        return configured == null || configured.isBlank() ? CategoryFolderNames.DEFAULT_LANGUAGE : configured;
    }

    /** The scope's folders, breadth first, the root first, bounded in depth and files. */
    private List<ScopeFolder> walk(UUID rootFolderId, Caller caller) {
        List<ScopeFolder> out = new ArrayList<>();
        Deque<Map.Entry<UUID, String>> pending = new ArrayDeque<>();
        pending.add(Map.entry(rootFolderId == null ? NULL_ROOT : rootFolderId, ""));
        int files = 0;
        while (!pending.isEmpty() && files < MAX_FILES) {
            Map.Entry<UUID, String> current = pending.poll();
            UUID id = current.getKey() == NULL_ROOT ? null : current.getKey();
            List<Document> children = blockWithAuth((id == null
                    ? documentRepository.findByParentIdIsNullAndActiveIsTrue()
                    : documentRepository.findByParentIdAndActiveIsTrue(id)).collectList(), caller);
            if (children == null) children = List.of();
            List<Document> folderFiles = children.stream().filter(d -> d.getType() == DocumentType.FILE).toList();
            List<Document> subFolders = children.stream().filter(d -> d.getType() == DocumentType.FOLDER).toList();
            out.add(new ScopeFolder(id, current.getValue(), folderFiles, subFolders));
            files += folderFiles.size();
            int depth = current.getValue().isEmpty() ? 0 : current.getValue().split("/").length;
            if (depth < MAX_DEPTH) {
                for (Document sub : subFolders) {
                    pending.add(Map.entry(sub.getId(), current.getValue().isEmpty() ? sub.getName() : current.getValue() + "/" + sub.getName()));
                }
            }
        }
        return out;
    }

    private static final UUID NULL_ROOT = new UUID(0, 0);

    private Map<UUID, String> categoriesOf(List<UUID> ids, Caller caller) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> out = new LinkedHashMap<>();
        for (int from = 0; from < ids.size(); from += 500) {
            List<AiDocumentInsight> rows = blockWithAuth(insightStore.findAll(ids.subList(from, Math.min(ids.size(), from + 500))).collectList(), caller);
            if (rows == null) continue;
            for (AiDocumentInsight row : rows) {
                if (row.getCategory() != null) out.put(row.getDocumentId(), row.getCategory().trim().toLowerCase(Locale.ROOT));
            }
        }
        return out;
    }

    private static <T> T blockWithAuth(Mono<T> mono, Caller caller) {
        return mono.contextWrite(ReactiveSecurityContextHolder.withAuthentication(caller.authentication())).block();
    }
}
