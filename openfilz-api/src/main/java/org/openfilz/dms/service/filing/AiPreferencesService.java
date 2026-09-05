package org.openfilz.dms.service.filing;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.dto.request.SaveAiPreferencesRequest;
import org.openfilz.dms.dto.response.AiPreferencesView;
import org.openfilz.dms.entity.UserAiPreferences;
import org.openfilz.dms.repository.UserAiPreferencesRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

/** The user's smart-filing switch, defaulting to the deployment's {@code default-for-users}. */
@Service
@Lazy
@RequiredArgsConstructor
public class AiPreferencesService {

    private final UserAiPreferencesRepository repository;
    private final AiProperties aiProperties;

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

    public Mono<UserAiPreferences> save(String userEmail, SaveAiPreferencesRequest request) {
        return get(userEmail).flatMap(current -> {
            boolean isNew = current.isNew();
            if (request.autoFile() != null) current.setAutoFile(request.autoFile());
            if (request.autoFileNewFolders() != null) current.setAutoFileNewFolders(request.autoFileNewFolders());
            current.setUpdatedAt(OffsetDateTime.now());
            current.setNew(isNew);
            return repository.save(current).map(saved -> {
                saved.setNew(false);
                return saved;
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

    public AiPreferencesView view(UserAiPreferences preferences, boolean autoFileAvailable) {
        return new AiPreferencesView(autoFileAvailable, preferences.isAutoFile(),
                preferences.isAutoFileNewFolders() && aiProperties.getAutoFile().isAllowNewFolders());
    }
}
