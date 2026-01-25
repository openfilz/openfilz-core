package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
public class SettingsServiceImpl implements SettingsService {

    @Value("${openfilz.soft-delete.active:false}")
    private Boolean softDelete;

    private final RecycleBinProperties recycleBinProperties;

    @Override
    public Mono<Settings> getSettings() {
        if(!softDelete || !recycleBinProperties.isEnabled()) {
            return emptySettings();
        }
        String autoCleanupInterval = recycleBinProperties.getAutoCleanupInterval();
        if(autoCleanupInterval == null) {
            return emptySettings();
        }
        autoCleanupInterval = autoCleanupInterval.trim();
        if(autoCleanupInterval.isEmpty() || autoCleanupInterval.equals("0")) {
            return emptySettings();
        }
        int i = autoCleanupInterval.indexOf((" "));
        if(i < 0) {
            return Mono.just(new Settings(Integer.parseInt(autoCleanupInterval)));
        }
        return Mono.just(new Settings(Integer.parseInt(autoCleanupInterval.substring(0, i))));
    }

    private Mono<Settings> emptySettings() {
        return Mono.just(new Settings(null));
    }
}
