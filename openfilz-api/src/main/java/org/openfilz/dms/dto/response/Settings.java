package org.openfilz.dms.dto.response;

import lombok.Builder;

@Builder
public record Settings(Integer emptyBinInterval, Integer fileQuotaMB, Integer userQuotaMB,
                       String language, String theme, boolean thumbnailsActive, boolean aiActive,
                       boolean aiUserSettingsEnabled, boolean signatureActive,
                       /** Recipient authentication methods this deployment can actually deliver. */
                       java.util.List<String> signatureAuthMethods,
                       /** True only when something in this deployment actually sends the reminders. */
                       boolean signatureRemindersActive,
                       /** True when the openfilz-cloud seal provider is configured — the Settings page shows the subscription card. */
                       boolean signatureCloudActive) {
}
