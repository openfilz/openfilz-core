package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.McpProperties;
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

    /** Mirrors spring.ai.mcp.server.streamable-http.mcp-endpoint, which the transport fixes at /mcp. */
    private static final String MCP_ENDPOINT_PATH = "/mcp";

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
    @Value("${openfilz.ai.insights.active:false}")
    private Boolean aiInsightsActive;

    @Value("${openfilz.ai.user-settings.enabled:false}")
    private Boolean aiUserSettingsEnabled;

    // e-Sign master switch — the frontend shows the Signatures menu + "Request signature" action from this.
    @Value("${openfilz.signature.active:false}")
    private Boolean signatureActive;

    // PDF tools master switch — the frontend shows the merge / split / rotate / organize actions from this.
    @Value("${openfilz.pdf-tools.active:true}")
    private Boolean pdfToolsActive;

    // When on, initiating signature requests also needs the SIGN_REQUESTER role — surfaced so the
    // frontend can hide the request/template actions from users without it (backend still enforces).
    @Value("${openfilz.signature.require-requester-role:false}")
    private Boolean signatureRequireRequesterRole;

    @Value("${openfilz.signature.seal.provider:self-signed-dev}")
    private String signatureSealProvider;

    @Value("${openfilz.signature.seal.cloud.api-key:}")
    private String signatureCloudApiKey;

    // Shared public demo deployments set this so the frontend can show the demo disclaimers
    // (shared-visibility warning, upsell links). Plain runtime flag — never a bean condition.
    @Value("${openfilz.demo-mode:false}")
    private Boolean demoMode;

    private final RecycleBinProperties recycleBinProperties;

    private final QuotaProperties quotaProperties;

    /** MCP server config — surfaced so a user can connect their own agent without asking an admin. */
    private final McpProperties mcpProperties;

    /**
     * Holds the public API base URL. The MCP endpoint is built from it exactly as
     * McpDiscoveryController builds the RFC 9728 {@code resource}, so what the settings page
     * shows and what a host discovers can never drift apart.
     */
    private final CommonProperties commonProperties;

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

    /**
     * The seal provider that will actually sign completed envelopes. Defaults to this
     * application's own sealer config; deployments where another component applies the
     * seal override this to report that component's provider instead.
     */
    protected String effectiveSealProvider() {
        return signatureSealProvider;
    }

    /**
     * True when the openfilz-cloud seal is configured end-to-end. The in-process cloud
     * sealer needs its API key; overrides may rely on the sealing component's own config.
     */
    protected boolean isCloudSealConfigured() {
        return "openfilz-cloud".equals(effectiveSealProvider())
                && signatureCloudApiKey != null && !signatureCloudApiKey.isBlank();
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
               .aiInsightsActive(Boolean.TRUE.equals(aiActive) && Boolean.TRUE.equals(aiInsightsActive))
               .signatureActive(Boolean.TRUE.equals(signatureActive))
               .pdfToolsActive(Boolean.TRUE.equals(pdfToolsActive))
               .signatureRequesterRoleRequired(Boolean.TRUE.equals(signatureActive) && Boolean.TRUE.equals(signatureRequireRequesterRole))
               .signatureAuthMethods(deliverableAuthMethods())
               .signatureRemindersActive(Boolean.TRUE.equals(signatureActive) && !reminderSenders.isEmpty())
               .signatureCloudActive(Boolean.TRUE.equals(signatureActive) && isCloudSealConfigured())
               .demoMode(Boolean.TRUE.equals(demoMode))
               .sealProvider(Boolean.TRUE.equals(signatureActive) ? effectiveSealProvider() : null)
               .mcpActive(mcpProperties.isActive())
               .mcpUrl(mcpProperties.isActive() ? mcpEndpointUrl() : null)
               .mcpMode(mcpProperties.isActive() ? mcpProperties.getMode().name() : null)
               .mcpAuthorizationServerUrl(mcpProperties.isActive() ? mcpProperties.getAuthorizationServerUrl() : null)
               .mcpClientId(mcpProperties.isActive() ? mcpProperties.getClientId() : null)
               .build());

    }

    /**
     * The URL an MCP host connects to. Same construction as the protected-resource metadata:
     * the public API base URL plus the transport's endpoint path.
     */
    private String mcpEndpointUrl() {
        String base = commonProperties.getApiPublicBaseUrl();
        if (base == null || base.isBlank()) {
            return null;
        }
        return (base.endsWith("/") ? base.substring(0, base.length() - 1) : base) + MCP_ENDPOINT_PATH;

    }

}
