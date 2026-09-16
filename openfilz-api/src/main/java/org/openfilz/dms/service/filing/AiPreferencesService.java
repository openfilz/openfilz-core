package org.openfilz.dms.service.filing;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.SaveAiPreferencesRequest;
import org.openfilz.dms.dto.response.AiPreferencesView;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.UserAiPreferences;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.UserAiPreferencesRepository;
import org.openfilz.dms.service.DocumentService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The user's smart-filing switch, defaulting to the deployment's {@code default-for-users}, and
 * their optional Inbox folder (design §13.5).
 */
@Slf4j
@Service
@Lazy
public class AiPreferencesService {

    private final UserAiPreferencesRepository repository;
    private final AiProperties aiProperties;
    /** Organisation-level gate (an extension's governance); setter-injected so the constructor stays as it is. */
    private volatile org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.insight.InsightsPolicy> insightsPolicyProvider;

    @org.springframework.beans.factory.annotation.Autowired
    public void setInsightsPolicyProvider(org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.insight.InsightsPolicy> provider) {
        this.insightsPolicyProvider = provider;
    }

    private Mono<Boolean> allowedByPolicy(String email) {
        org.springframework.beans.factory.ObjectProvider<org.openfilz.dms.service.insight.InsightsPolicy> provider = insightsPolicyProvider;
        org.openfilz.dms.service.insight.InsightsPolicy policy = provider == null ? null : provider.getIfAvailable();
        return policy == null ? Mono.just(true) : policy.autoFileAllowed(email).defaultIfEmpty(true).onErrorReturn(false);
    }
    private final DocumentRepository documentRepository;
    private final DocumentService documentService;

    public AiPreferencesService(UserAiPreferencesRepository repository, AiProperties aiProperties,
                                DocumentRepository documentRepository, DocumentService documentService) {
        this.repository = repository;
        this.aiProperties = aiProperties;
        this.documentRepository = documentRepository;
        this.documentService = documentService;
    }

    public Mono<UserAiPreferences> get(String userEmail) {
        AiProperties.AutoFile config = aiProperties.getAutoFile();
        UserAiPreferences defaults = UserAiPreferences.builder()
                .userEmail(userEmail)
                .autoFile(config.isDefaultForUsers())
                .autoFileNewFolders(config.isAllowNewFolders())
                .isNew(true)
                .build();
        return userEmail == null ? Mono.just(defaults) : repository.findById(userEmail).defaultIfEmpty(defaults);
    }

    /** Save with no language hint: an Inbox created here is named in the deployment's default language. */
    public Mono<UserAiPreferences> save(String userEmail, SaveAiPreferencesRequest request) {
        return save(userEmail, request, null);
    }

    /**
     * @param acceptLanguage the request's {@code Accept-Language} header (may be null): the language an
     *                       Inbox created by this call is named in, before {@code auto-file.default-language}
     * @throws IllegalStateException when the request turns the Inbox on but the deployment does not offer it
     */
    public Mono<UserAiPreferences> save(String userEmail, SaveAiPreferencesRequest request, String acceptLanguage) {
        if (Boolean.TRUE.equals(request.inbox()) && !inboxOffered()) {
            return Mono.error(new IllegalStateException("The Inbox is not enabled on this deployment"));
        }
        return get(userEmail).flatMap(current -> {
            boolean isNew = current.isNew();
            if (request.autoFile() != null) current.setAutoFile(request.autoFile());
            if (request.autoFileNewFolders() != null) current.setAutoFileNewFolders(request.autoFileNewFolders());
            Mono<UserAiPreferences> prepared = Mono.just(current);
            if (Boolean.TRUE.equals(request.inbox())) {
                // As the caller, through the ordinary folder API: audit, ownership and name-clash
                // rules are the user's own, and the folder is a plain folder they may rename or delete.
                prepared = ensureInbox(current, acceptLanguage).thenReturn(current);
            } else if (Boolean.FALSE.equals(request.inbox())) {
                current.setInboxFolderId(null);   // forgotten, never deleted: whatever is in it stays
            }
            return prepared.flatMap(p -> {
                p.setUpdatedAt(OffsetDateTime.now());
                p.setNew(isNew);
                return repository.save(p).map(saved -> {
                    saved.setNew(false);
                    return saved;
                });
            });
        });
    }

    /** Whether uploads of this user are filed when the request does not say (the user's switch). */
    public Mono<Boolean> autoFileEnabled(String userEmail) {
        return get(userEmail).map(UserAiPreferences::isAutoFile);
    }

    /** Whether filing may create folders for this user: their option, capped by the deployment's. */
    public Mono<Boolean> newFoldersAllowed(String userEmail) {
        return get(userEmail).map(p -> p.isAutoFileNewFolders() && aiProperties.getAutoFile().isAllowNewFolders());
    }

    /** True when the deployment offers the Inbox: smart filing on and {@code auto-file.inbox.enabled}. */
    public boolean inboxOffered() {
        return aiProperties.isActive() && aiProperties.getAutoFile().isActive() && aiProperties.getAutoFile().getInbox().isEnabled();
    }

    /**
     * The user's Inbox folder id when they have one <em>and it still exists</em> as an active
     * folder; empty otherwise (no Inbox, feature off, or the folder was deleted — then the user has
     * none until they turn it on again). One row read plus one folder read; blocking callers
     * (the filing worker) may {@code block()} it.
     */
    public Mono<UUID> inboxFolderId(String userEmail) {
        if (!inboxOffered()) {
            return Mono.empty();
        }
        return get(userEmail)
                .flatMap(p -> Mono.justOrEmpty(p.getInboxFolderId()))
                .flatMap(this::liveFolder)
                .map(Document::getId);
    }

    /** The preferences as the API shows them; reactive because the Inbox must still exist to count. */
    public Mono<AiPreferencesView> view(UserAiPreferences preferences, boolean autoFileAvailable) {
        return allowedByPolicy(preferences.getUserEmail())
                .flatMap(allowed -> viewFor(preferences, autoFileAvailable && allowed));
    }

    private Mono<AiPreferencesView> viewFor(UserAiPreferences preferences, boolean autoFileAvailable) {
        boolean inboxAvailable = autoFileAvailable && aiProperties.getAutoFile().getInbox().isEnabled();
        boolean newFolders = preferences.isAutoFileNewFolders() && aiProperties.getAutoFile().isAllowNewFolders();
        Mono<Optional<UUID>> inbox = !inboxAvailable || preferences.getInboxFolderId() == null
                ? Mono.just(Optional.empty())
                : liveFolder(preferences.getInboxFolderId()).map(f -> Optional.of(f.getId())).defaultIfEmpty(Optional.empty());
        return inbox.map(id -> new AiPreferencesView(autoFileAvailable, preferences.isAutoFile(), newFolders,
                inboxAvailable, id.isPresent(), id.orElse(null)));
    }

    // ── the Inbox folder ────────────────────────────────────────────────────

    /**
     * Make sure the preferences point at a live Inbox: keep the current one when it still exists,
     * else reuse a root folder already bearing the Inbox name (a user who deleted their row, or
     * created the folder by hand), else create it at the root as the caller.
     */
    private Mono<Void> ensureInbox(UserAiPreferences current, String acceptLanguage) {
        Mono<Document> existing = current.getInboxFolderId() == null ? Mono.empty() : liveFolder(current.getInboxFolderId());
        String name = aiProperties.getAutoFile().getInbox().nameFor(inboxLanguage(acceptLanguage));
        return existing
                .switchIfEmpty(Mono.defer(() -> rootFolderNamed(name)))
                .switchIfEmpty(Mono.defer(() -> documentService.createFolder(new CreateFolderRequest(name, null))
                        .flatMap(created -> documentRepository.findById(created.id()))
                        .doOnNext(f -> log.info("[AUTOFILE] Inbox '{}' created for {}", f.getName(), current.getUserEmail()))))
                .doOnNext(folder -> current.setInboxFolderId(folder.getId()))
                .then();
    }

    /** An active FOLDER at the root with exactly this name (the folder API forbids two of them). */
    private Mono<Document> rootFolderNamed(String name) {
        return documentRepository.findByNameIgnoreCaseAndActiveTrue(name)
                .filter(d -> d.getType() == DocumentType.FOLDER && d.getParentId() == null && name.equals(d.getName()))
                .next();
    }

    /** The folder when it is still an active folder; empty when deleted (soft or hard) or not a folder any more. */
    private Mono<Document> liveFolder(UUID folderId) {
        return documentRepository.findByIdAndActive(folderId, true)
                .filter(d -> d.getType() == DocumentType.FOLDER);
    }

    /**
     * The language the Inbox is named in: the first range of {@code Accept-Language} when there is
     * one, else the deployment's {@code auto-file.default-language}, else English.
     */
    String inboxLanguage(String acceptLanguage) {
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            try {
                List<Locale.LanguageRange> ranges = Locale.LanguageRange.parse(acceptLanguage);
                if (!ranges.isEmpty() && !"*".equals(ranges.getFirst().getRange())) {
                    return ranges.getFirst().getRange();
                }
            } catch (IllegalArgumentException ignored) {
                // a malformed header names no language
            }
        }
        String fallback = aiProperties.getAutoFile().getDefaultLanguage();
        return fallback == null || fallback.isBlank() ? CategoryFolderNames.DEFAULT_LANGUAGE : fallback;
    }
}
