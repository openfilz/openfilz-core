package org.openfilz.dms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnlyOfficeJwtSecretStartupGuardTest {

    private static final String PUBLISHED_DEFAULT = "openfilz-onlyoffice-jwt-secret-2024";
    private static final String GOOD_SECRET = "3yQm9v0kq0p7Zr2c8Q1x6G5nA4tB2uW9sE7dH3jK1lM0";

    @Test
    @DisplayName("a blank secret refuses startup, naming the variable and the generator")
    void blankSecretIsRefused() {
        for (String blank : new String[]{null, "", "   "}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> new OnlyOfficeJwtSecretStartupGuard(enabledWith(blank)).afterPropertiesSet(),
                    "secret <" + blank + ">");
            assertTrue(ex.getMessage().contains("not set"), ex.getMessage());
            assertTrue(ex.getMessage().contains("ONLYOFFICE_JWT_SECRET"), ex.getMessage());
            assertTrue(ex.getMessage().contains("openssl rand -hex 32"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("the published default and the other example values refuse startup, whatever the case")
    void publishedValuesAreRefused() {
        for (String known : new String[]{PUBLISHED_DEFAULT, "OPENFILZ-ONLYOFFICE-JWT-SECRET-2024",
                "change-this-onlyoffice-jwt-secret", "CHANGE_ME_ONLYOFFICE_JWT_SECRET!", "change_me",
                "changeme-please-this-is-long-enough-already", "secret", "default-secret",
                "your-onlyoffice-jwt-secret"}) {
            IllegalStateException ex = assertThrows(IllegalStateException.class,
                    () -> OnlyOfficeJwtSecretStartupGuard.check(enabledWith(known)), "secret " + known);
            assertTrue(ex.getMessage().contains("publicly known"), ex.getMessage());
        }
    }

    @Test
    @DisplayName("a private but short secret refuses startup")
    void shortSecretIsRefused() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> OnlyOfficeJwtSecretStartupGuard.check(enabledWith("private-but-short-1234")));
        assertTrue(ex.getMessage().contains("too short"), ex.getMessage());
        assertTrue(ex.getMessage().contains("32"), ex.getMessage());
    }

    @Test
    @DisplayName("a long private secret is accepted (32 characters is enough)")
    void longSecretIsAccepted() {
        assertDoesNotThrow(() -> new OnlyOfficeJwtSecretStartupGuard(enabledWith(GOOD_SECRET)).afterPropertiesSet());
        assertDoesNotThrow(() -> OnlyOfficeJwtSecretStartupGuard.check(enabledWith("a".repeat(32))));
        assertNull(OnlyOfficeJwtSecretStartupGuard.rejectionReason(GOOD_SECRET));
        // The secret the OnlyOffice integration tests run with must stay acceptable
        assertNull(OnlyOfficeJwtSecretStartupGuard.rejectionReason("openfilz-onlyoffice-test-jwt-secret-2024"));
    }

    @Test
    @DisplayName("OnlyOffice disabled, or JWT disabled, ignores the secret entirely")
    void disabledIntegrationIgnoresTheSecret() {
        OnlyOfficeProperties off = enabledWith(PUBLISHED_DEFAULT);
        off.setEnabled(false);
        assertDoesNotThrow(() -> new OnlyOfficeJwtSecretStartupGuard(off).afterPropertiesSet());

        OnlyOfficeProperties jwtOff = enabledWith(null);
        jwtOff.getJwt().setEnabled(false);
        assertDoesNotThrow(() -> OnlyOfficeJwtSecretStartupGuard.check(jwtOff));
    }

    private static OnlyOfficeProperties enabledWith(String secret) {
        OnlyOfficeProperties properties = new OnlyOfficeProperties();
        properties.setEnabled(true);
        properties.getJwt().setEnabled(true);
        properties.getJwt().setSecret(secret);
        return properties;
    }
}
