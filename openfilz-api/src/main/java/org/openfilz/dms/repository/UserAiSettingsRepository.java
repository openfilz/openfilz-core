package org.openfilz.dms.repository;

import org.openfilz.dms.entity.UserAiSettings;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserAiSettingsRepository extends ReactiveCrudRepository<UserAiSettings, String> {
}
