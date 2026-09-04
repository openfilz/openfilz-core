package org.openfilz.dms.repository;

import org.openfilz.dms.entity.UserAiPreferences;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

public interface UserAiPreferencesRepository extends ReactiveCrudRepository<UserAiPreferences, String> {
}
