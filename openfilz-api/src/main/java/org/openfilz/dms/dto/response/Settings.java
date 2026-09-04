package org.openfilz.dms.dto.response;

import lombok.Builder;

@Builder(toBuilder = true)
public record Settings(Integer emptyBinInterval, Integer fileQuotaMB, Integer userQuotaMB,
                       String language, String theme, boolean thumbnailsActive, boolean aiActive,
                       boolean aiUserSettingsEnabled,
                       /** True when tier-2 document insights (AI category / summary at upload) are on: the frontend shows the Insights section and category facets. */
                       boolean aiInsightsActive,
                       boolean signatureActive,
                       /** True when the PDF tools (merge / split / rotate / organize pages) are enabled — the frontend shows the PDF actions. */
                       boolean pdfToolsActive,
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
                       String sealProvider,
                       /** True when this deployment runs the MCP server — the frontend shows the "connect your AI tool" panel. */
                       boolean mcpActive,
                       /** Public URL an MCP host connects to (null when MCP is off) — same value the RFC 9728 metadata advertises. */
                       String mcpUrl,
                       /** READ_ONLY or READ_WRITE (null when MCP is off) — tells the user which tools their agent will actually get. */
                       String mcpMode,
                       /** Keycloak realm URL the MCP host authenticates against (null when MCP is off). */
                       String mcpAuthorizationServerUrl,
                       /** Keycloak client id to enter in hosts that cannot self-register, e.g. Claude Desktop (null when MCP is off). */
                       String mcpClientId) {
}
