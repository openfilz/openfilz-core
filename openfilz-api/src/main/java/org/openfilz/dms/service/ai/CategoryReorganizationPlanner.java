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
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
 * {@code other} or without a category, and a folder whose files are all of one kind — including
 * the scope folder itself when it holds nothing else or is named after that kind ({@code CV}
 * never gets {@code CV/CVs}). A scope folder with sub-folders and loose files of one kind, like
 * the root level, gets a folder for them. Each folder left alone is counted under its
 * {@link Skip} reason, so a caller can say why nothing moved. Only what the caller may read
 * ({@link AiAccessPolicy}) is counted or proposed.
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
    private final AiAccessPolicy accessPolicy;
    private final CategoryFolderNames folderNames;

    public CategoryReorganizationPlanner(DocumentRepository documentRepository, DocumentInsightStore insightStore,
                                         ReorganizationPlanService planService, AiProperties aiProperties,
                                         AiAccessPolicy accessPolicy) {
        this.documentRepository = documentRepository;
        this.insightStore = insightStore;
        this.planService = planService;
        this.aiProperties = aiProperties;
        this.accessPolicy = accessPolicy;
        this.folderNames = new CategoryFolderNames(aiProperties.getAutoFile().getFolderNames());
    }

    /** One folder of the scope: its path relative to the scope root ("" for the root itself), its files and sub-folders. */
    record ScopeFolder(UUID id, String relativePath, List<Document> files, List<Document> folders) {
    }

    /** Why a folder holding files was left as it is. */
    public enum Skip {
        /** Its classified files are all of one kind: it is their home already. */
        ONE_KIND,
        /** One kind holds at least {@code split-min-purity} of its classified files; the others are odd ones out. */
        MOSTLY_ONE_KIND,
        /** Fewer than {@code split-min-files} classified files: too few to be worth sub-folders. */
        TOO_FEW_FILES,
        /** Several kinds, none with {@code split-min-group} files. */
        KINDS_TOO_SMALL,
        /** None of its files has a kind (other than {@code other}). */
        NO_KIND
    }

    /**
     * What the planner found: the request to propose (null when nothing to do), a human summary,
     * and how many folders holding files were left alone, per reason.
     */
    public record Draft(ReorganizationPlanRequest request, int mixedFolders, int moves, List<String> newFolders, String language,
                        Map<Skip, Integer> skipped) {
        public boolean isEmpty() {
            return request == null || request.moves().isEmpty();
        }
    }

    /** Propose the by-kind split of a scope as a stored plan; a view with no id when nothing needs splitting. */
    public ReorganizationPlanView propose(UUID rootFolderId, UUID conversationId, Caller caller) {
        Draft draft = draft(rootFolderId, caller);
        if (draft.isEmpty()) {
            return new ReorganizationPlanView(null, ReorganizationPlanService.STATUS_PROPOSED, rootFolderId,
                    planService.pathOf(rootFolderId, caller), nothingToSplit(draft.skipped(), "this scope"),
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
        List<ScopeFolder> scope = visibleTo(caller, walk(rootFolderId, caller));
        List<String> allFolderNames = new ArrayList<>();
        scope.forEach(f -> f.folders().forEach(d -> allFolderNames.add(d.getName())));
        String language = folderNames.languageOf(allFolderNames)
                .orElse(defaultLanguage());

        // A scope folder named after a kind ("CV", "Factures") is the home of that kind
        Optional<String> rootKind = rootFolderId == null ? Optional.empty()
                : Optional.ofNullable(blockWithAuth(documentRepository.findById(rootFolderId), caller))
                        .flatMap(root -> folderNames.categoryOf(root.getName()));

        List<Move> moves = new ArrayList<>();
        List<String> created = new ArrayList<>();
        Map<Skip, Integer> skipped = new EnumMap<>(Skip.class);
        int mixed = 0;
        Map<UUID, String> categories = categoriesOf(scope.stream().flatMap(f -> f.files().stream()).map(Document::getId).toList(), caller);
        for (ScopeFolder folder : scope) {
            if (folder.files().isEmpty()) continue;
            Map<String, List<Document>> byKind = new LinkedHashMap<>();
            for (Document file : folder.files()) {
                String kind = categories.get(file.getId());
                if (kind == null || InsightResult.OTHER.equals(kind)) continue;
                byKind.computeIfAbsent(kind, k -> new ArrayList<>()).add(file);
            }
            Skip skip = skipOf(folder, byKind, config, rootKind);
            if (skip != null) {
                skipped.merge(skip, 1, Integer::sum);
                continue;
            }
            List<String> kinds = byKind.entrySet().stream()
                    .filter(e -> e.getValue().size() >= Math.max(1, config.getSplitMinGroup()))
                    .map(Map.Entry::getKey).toList();
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
            return new Draft(null, 0, 0, List.of(), language, skipped);
        }
        String rationale = "Split " + mixed + " folder" + (mixed == 1 ? "" : "s") + " holding documents of several kinds into one "
                + "sub-folder per kind, named in " + language + " like the existing folders: " + String.join(", ", created.isEmpty()
                ? List.of("existing folders reused") : created) + ".";
        ReorganizationPlanRequest request = new ReorganizationPlanRequest(
                rootFolderId == null ? null : rootFolderId.toString(), moves, created, rationale);
        return new Draft(request, mixed, moves.size(), created, language, skipped);
    }

    /** Why a folder holding files is left alone, or null when its kinds are worth a sub-folder each. */
    private static Skip skipOf(ScopeFolder folder, Map<String, List<Document>> byKind, AiProperties.Reorganization config,
                               Optional<String> rootKind) {
        int categorised = byKind.values().stream().mapToInt(List::size).sum();
        if (categorised == 0) {
            return Skip.NO_KIND;
        }
        if (byKind.size() == 1 && folder.id() != null) {
            // One kind in its own folder is at home. The scope folder too when it holds nothing else
            // or is named after that kind; with sub-folders beside them, its loose files get their folder
            boolean scopeFolder = folder.relativePath().isEmpty();
            String kind = byKind.keySet().iterator().next();
            if (!scopeFolder || folder.folders().isEmpty() || rootKind.filter(kind::equalsIgnoreCase).isPresent()) {
                return Skip.ONE_KIND;
            }
        }
        if (categorised < Math.max(1, config.getSplitMinFiles())) {
            return Skip.TOO_FEW_FILES;
        }
        int dominant = byKind.values().stream().mapToInt(List::size).max().orElse(0);
        if (byKind.size() > 1 && dominant >= config.getSplitMinPurity() * categorised) {
            // A home already: the odd files out are not worth a folder each
            return Skip.MOSTLY_ONE_KIND;
        }
        boolean anyGroup = byKind.values().stream().anyMatch(files -> files.size() >= Math.max(1, config.getSplitMinGroup()));
        return anyGroup ? null : Skip.KINDS_TOO_SMALL;
    }

    /**
     * Why nothing was proposed, in plain English (the model and the logs read it; a UI should
     * translate {@link Draft#skipped()} instead).
     *
     * @param scope how to name the scope, e.g. "this scope" or "the 4 selected folders"
     */
    public String nothingToSplit(Map<Skip, Integer> skipped, String scope) {
        if (skipped == null || skipped.isEmpty()) {
            return "There is no document to sort in " + scope + ".";
        }
        if (skipped.size() == 1 && skipped.containsKey(Skip.ONE_KIND)) {
            return "Every folder of " + scope + " already holds documents of one kind.";
        }
        AiProperties.Reorganization config = aiProperties.getReorganization();
        List<String> parts = new ArrayList<>();
        skipped.forEach((skip, count) -> parts.add(count + " folder" + (count == 1 ? " " : "s ") + switch (skip) {
            case ONE_KIND -> (count == 1 ? "holds" : "hold") + " documents of one kind already";
            case MOSTLY_ONE_KIND -> (count == 1 ? "holds" : "hold") + " mostly documents of one kind";
            case TOO_FEW_FILES -> (count == 1 ? "holds" : "hold") + " fewer than " + Math.max(1, config.getSplitMinFiles())
                    + " classified documents, too few to split";
            case KINDS_TOO_SMALL -> (count == 1 ? "mixes" : "mix") + " kinds with fewer than " + Math.max(1, config.getSplitMinGroup())
                    + " documents of each";
            case NO_KIND -> (count == 1 ? "holds" : "hold") + " documents without a kind";
        }));
        return "Nothing to split in " + scope + ": " + String.join("; ", parts) + ".";
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

    /**
     * The scope as the caller may see it: a walk reads the tree as it is stored, so sub-folders and
     * files the caller cannot read are dropped before anything is counted or proposed. The scope
     * root stays (the caller named it; its files are filtered like the others).
     */
    private List<ScopeFolder> visibleTo(Caller caller, List<ScopeFolder> scope) {
        if (accessPolicy.permitAll()) {
            return scope;
        }
        List<UUID> ids = new ArrayList<>();
        for (ScopeFolder folder : scope) {
            if (folder.id() != null && !folder.relativePath().isEmpty()) ids.add(folder.id());
            folder.files().forEach(file -> ids.add(file.getId()));
        }
        Set<UUID> readable = new HashSet<>();
        for (int from = 0; from < ids.size(); from += 500) {
            Set<UUID> chunk = blockWithAuth(accessPolicy.readable(ids.subList(from, Math.min(ids.size(), from + 500)), caller.email()), caller);
            if (chunk != null) readable.addAll(chunk);
        }
        List<ScopeFolder> out = new ArrayList<>();
        for (ScopeFolder folder : scope) {
            if (!folder.relativePath().isEmpty() && !readable.contains(folder.id())) continue;
            out.add(new ScopeFolder(folder.id(), folder.relativePath(),
                    folder.files().stream().filter(file -> readable.contains(file.getId())).toList(),
                    folder.folders().stream().filter(sub -> readable.contains(sub.getId())).toList()));
        }
        return out;
    }

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
