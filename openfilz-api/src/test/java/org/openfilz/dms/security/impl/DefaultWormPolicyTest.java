package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The global WORM perimeter and its start-up guards, extracted from the former
 * {@code WormSecurityServiceImpl} (D1).
 * <p>
 * The load-bearing assertion here is {@link #enterpriseDeploymentIsNoLongerRefused()}: WORM used to
 * refuse to start whenever {@code openfilz.features.custom-access=true}, i.e. for every Enterprise
 * deployment, while the product page advertised the mode. Decoupling the two is the whole point of D1.
 */
class DefaultWormPolicyTest {

    private DefaultWormPolicy policy;

    @BeforeEach
    void setUp() {
        policy = new DefaultWormPolicy();
        ReflectionTestUtils.setField(policy, "wormMode", true);
        ReflectionTestUtils.setField(policy, "noAuth", false);
        ReflectionTestUtils.setField(policy, "calculateChecksum", true);
    }

    @Test
    void enforcedFollowsTheFlag() {
        assertTrue(policy.isEnforced());
        ReflectionTestUtils.setField(policy, "wormMode", false);
        assertFalse(policy.isEnforced());
    }

    @Test
    void globalPerimeterCoversEveryRequest() {
        assertTrue(policy.covers(HttpMethod.DELETE, "/documents/x"));
        assertTrue(policy.covers(HttpMethod.GET, "/documents/x"));
    }

    @Test
    void anInactivePerimeterCoversNothing() {
        ReflectionTestUtils.setField(policy, "wormMode", false);
        assertFalse(policy.covers(HttpMethod.DELETE, "/documents/x"));
    }

    @Test
    void enterpriseDeploymentIsNoLongerRefused() {
        // D1: custom-access=true used to be a start-up failure. It must now be a supported combination.
        assertDoesNotThrow(() -> policy.init());
    }

    @Test
    void validConfigurationPasses() {
        assertDoesNotThrow(() -> policy.init());
    }

    @Test
    void wormWithoutAuthenticationIsRefused() {
        ReflectionTestUtils.setField(policy, "noAuth", true);
        assertThrows(IllegalStateException.class, () -> policy.init());
    }

    /** C1 — an archiving perimeter without a fingerprint proves nothing. */
    @Test
    void wormWithoutChecksumIsRefused() {
        ReflectionTestUtils.setField(policy, "calculateChecksum", false);
        assertThrows(IllegalStateException.class, () -> policy.init());
    }

    @Test
    void guardsDoNotApplyWhenWormIsOff() {
        ReflectionTestUtils.setField(policy, "wormMode", false);
        ReflectionTestUtils.setField(policy, "noAuth", true);
        ReflectionTestUtils.setField(policy, "calculateChecksum", false);
        assertDoesNotThrow(() -> policy.init());
    }
}
