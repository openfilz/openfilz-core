package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.service.SettingsService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class SettingsServiceImpl implements SettingsService {

    @Value("${openfilz.soft-delete.active:false}")
    private Boolean softDelete;

    @Value("${openfilz.thumbnail.active:false}")
    private Boolean thumbnailActive;

    // Single switch for the AI feature: the frontend shows the chat UI from this, so enabling
    // the backend flag is all it takes (no separate NG_APP_* toggle to keep in sync).
    @Value("${openfilz.ai.active:false}")
    private Boolean aiActive;

    // BYOK: lets users override the chat LLM with their own provider + API key. Read at
    // runtime (plain @Value, no conditional bean) so it stays a deployment toggle in native images.
    @Value("${openfilz.ai.user-settings.enabled:false}")
    private Boolean aiUserSettingsEnabled;

    // e-Sign master switch — the frontend shows the Signatures menu + "Request signature" action from this.
    @Value("${openfilz.signature.active:false}")
    private Boolean signatureActive;

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
       return Mono.just(Settings.builder()
               .emptyBinInterval(emptyBinInterval)
               .fileQuotaMB(quotaProperties.getFileUpload())
               .userQuotaMB(quotaProperties.getUser())
               .thumbnailsActive(thumbnailActive)
               .aiActive(aiActive)
               .aiUserSettingsEnabled(aiActive && aiUserSettingsEnabled)
               .signatureActive(Boolean.TRUE.equals(signatureActive))
               .build());

    }

}
