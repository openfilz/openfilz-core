package org.openfilz.dms.dto.response;

import lombok.Builder;

@Builder(toBuilder = true)
public record Settings(Integer emptyBinInterval, Integer fileQuotaMB, Integer userQuotaMB,
                       String language, String theme, boolean thumbnailsActive, boolean aiActive,
                       boolean aiUserSettingsEnabled, boolean signatureActive,
                       /** True when initiating signature requests additionally requires the SIGN_REQUESTER role — the frontend hides the request/template actions from users without it. */
                       boolean signatureRequesterRoleRequired,
                       /** Recipient authentication methods this deployment can actually deliver. */
                       java.util.List<String> signatureAuthMethods,
                       /** True only when something in this deployment actually sends the reminders. */
                       boolean signatureRemindersActive,
                       /** True when the openfilz-cloud seal provider is configured — the Settings page shows the subscription card. */
                       boolean signatureCloudActive,
                       /** True on shared public demo deployments — the frontend shows the demo disclaimers. */
                       boolean demoMode,
                       /** Effective e-Sign seal provider id (null when e-Sign is off) — lets the frontend warn about the untrusted dev seal. */
                       String sealProvider) {
}
