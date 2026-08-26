package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.service.SettingsService;
import org.openfilz.dms.service.signature.SignatureOtpSender;
import org.openfilz.dms.service.signature.SignatureReminderSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

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

    @Value("${openfilz.signature.seal.provider:self-signed-dev}")
    private String signatureSealProvider;

    @Value("${openfilz.signature.seal.cloud.api-key:}")
    private String signatureCloudApiKey;

    private final RecycleBinProperties recycleBinProperties;

    private final QuotaProperties quotaProperties;

    /** Senders registered for the e-Sign OTP channels — drives what the UI may offer. */
    private final List<SignatureOtpSender> otpSenders;

    /**
     * Empty in the core: it records an envelope's reminder cadence but has no scheduler to act on
     * it. The Enterprise edition contributes one, which is what turns the setting on.
     */
    private final List<SignatureReminderSender> reminderSenders;

    /**
     * NONE always works; the OTP channels are advertised only when a sender is registered
     * <em>and</em> configured, so the UI never offers a method the server would refuse.
     */
    protected List<String> deliverableAuthMethods() {
        List<String> methods = new ArrayList<>();
        methods.add(SignatureAuthMethod.NONE.name());
        for (SignatureAuthMethod method : SignatureAuthMethod.values()) {
            if (method != SignatureAuthMethod.NONE
                    && otpSenders.stream().anyMatch(sender -> sender.supports(method))) {
                methods.add(method.name());
            }
        }
        return methods;
    }

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
               .signatureAuthMethods(deliverableAuthMethods())
               .signatureRemindersActive(Boolean.TRUE.equals(signatureActive) && !reminderSenders.isEmpty())
               .signatureCloudActive(Boolean.TRUE.equals(signatureActive)
                       && "openfilz-cloud".equals(signatureSealProvider)
                       && signatureCloudApiKey != null && !signatureCloudApiKey.isBlank())
               .build());

    }

}
