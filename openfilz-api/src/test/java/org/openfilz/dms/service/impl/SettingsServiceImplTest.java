package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.McpProperties;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code aiActive} is the contract openfilz-web gates its chat UI on — the frontend has no AI
 * toggle of its own — so the flag has to reach the payload straight from {@code openfilz.ai.active}.
 * The MCP block is the same kind of contract for the "connect your AI tool" panel.
 */
class SettingsServiceImplTest {

    private Settings getSettings(boolean aiActive) {
        return getSettings(aiActive, false);
    }

    private Settings getSettings(boolean aiActive, boolean aiUserSettingsEnabled) {
        return getSettings(aiActive, aiUserSettingsEnabled, new McpProperties(), new CommonProperties());
    }

    private Settings getSettings(boolean aiActive, boolean aiUserSettingsEnabled,
                                 McpProperties mcpProperties, CommonProperties commonProperties) {
        SettingsServiceImpl service = new SettingsServiceImpl(new RecycleBinProperties(), new QuotaProperties(), new org.openfilz.dms.config.AiProperties(),
                mcpProperties, commonProperties, java.util.List.of(), java.util.List.of());
        ReflectionTestUtils.setField(service, "softDelete", false);
        ReflectionTestUtils.setField(service, "thumbnailActive", false);
        ReflectionTestUtils.setField(service, "aiActive", aiActive);
        ReflectionTestUtils.setField(service, "aiUserSettingsEnabled", aiUserSettingsEnabled);

        Settings settings = service.getSettings().block();
        assertNotNull(settings);
        return settings;
    }

    @Test
    void aiActive_isTrueWhenTheFeatureIsOn() {
        assertTrue(getSettings(true).aiActive());
    }

    @Test
    void aiActive_isFalseWhenTheFeatureIsOff() {
        assertFalse(getSettings(false).aiActive());
    }

    @Test
    void aiUserSettings_followsItsFlagWhenAiIsActive() {
        assertTrue(getSettings(true, true).aiUserSettingsEnabled());
        assertFalse(getSettings(true, false).aiUserSettingsEnabled());
    }

    /** BYOK without the AI feature makes no sense — the flag must stay off. */
    @Test
    void aiUserSettings_isFalseWhenAiIsInactive() {
        assertFalse(getSettings(false, true).aiUserSettingsEnabled());
    }

    /**
     * The endpoint has to match what McpDiscoveryController advertises as the RFC 9728
     * {@code resource}, or the settings page hands out an address remote hosts never
     * discovered. Same construction on both sides, trailing slash included.
     */
    @Test
    void mcp_reportsTheEndpointAndClientWhenActive() {
        McpProperties mcp = new McpProperties();
        mcp.setActive(true);
        mcp.setMode(McpProperties.Mode.READ_WRITE);
        mcp.setAuthorizationServerUrl("https://auth.example.com/realms/openfilz");
        CommonProperties common = new CommonProperties();
        common.setApiPublicBaseUrl("https://api.example.com/");

        Settings settings = getSettings(false, false, mcp, common);

        assertTrue(settings.mcpActive());
        assertEquals("https://api.example.com/mcp", settings.mcpUrl());
        assertEquals("READ_WRITE", settings.mcpMode());
        assertEquals("https://auth.example.com/realms/openfilz", settings.mcpAuthorizationServerUrl());
        assertEquals("openfilz-mcp", settings.mcpClientId());
    }

    /** A deployment that does not run MCP must advertise nothing at all, not a stale endpoint. */
    @Test
    void mcp_reportsNothingWhenInactive() {
        Settings settings = getSettings(true, true);

        assertFalse(settings.mcpActive());
        assertNull(settings.mcpUrl());
        assertNull(settings.mcpMode());
        assertNull(settings.mcpAuthorizationServerUrl());
        assertNull(settings.mcpClientId());
    }
}
