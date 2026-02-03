package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.QuotaProperties;
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

    private final QuotaProperties quotaProperties;

    @Override
    public Mono<Settings> getSettings() {
        Integer emptyBinInterval = null;
        if(softDelete && recycleBinProperties.isEnabled()) {
            String autoCleanupInterval = recycleBinProperties.getAutoCleanupInterval();
            if(autoCleanupInterval != null) {
                autoCleanupInterval = autoCleanupInterval.trim();
                if(!autoCleanupInterval.isEmpty() && !autoCleanupInterval.equals("0")) {
                    int i = autoCleanupInterval.indexOf((" "));
                    if(i < 0) {
                        emptyBinInterval = Integer.parseInt(autoCleanupInterval);
                    } else {
                        emptyBinInterval = Integer.parseInt(autoCleanupInterval.substring(0, i));
                    }
                }
            }
        }
       return Mono.just(new Settings(emptyBinInterval, quotaProperties.getFileUpload(), quotaProperties.getUser()));

    }

}
