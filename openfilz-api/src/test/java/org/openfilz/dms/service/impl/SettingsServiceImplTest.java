package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.dto.response.Settings;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code aiActive} is the contract openfilz-web gates its chat UI on — the frontend has no AI
 * toggle of its own — so the flag has to reach the payload straight from {@code openfilz.ai.active}.
 */
class SettingsServiceImplTest {

    private Settings getSettings(boolean aiActive) {
        SettingsServiceImpl service = new SettingsServiceImpl(new RecycleBinProperties(), new QuotaProperties());
        ReflectionTestUtils.setField(service, "softDelete", false);
        ReflectionTestUtils.setField(service, "thumbnailActive", false);
        ReflectionTestUtils.setField(service, "aiActive", aiActive);

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
}
