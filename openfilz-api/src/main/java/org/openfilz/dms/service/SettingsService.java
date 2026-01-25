package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.Settings;
import reactor.core.publisher.Mono;

public interface SettingsService {
    Mono<Settings> getSettings();
}
