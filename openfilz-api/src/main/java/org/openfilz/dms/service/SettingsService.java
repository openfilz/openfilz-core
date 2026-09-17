package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.Settings;
import reactor.core.publisher.Mono;

public interface SettingsService {

    /**
     * The settings, the category labels in the first supported language among the candidates
     * (an {@code Accept-Language} header, a language code) — English when none is supported.
     */
    Mono<Settings> getSettings(String... languages);

    default Mono<Settings> getSettings() {
        return getSettings((String) null);
    }
}
