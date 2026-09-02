package org.openfilz.dms.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.DownloadTokenProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the security posture of {@link DownloadTokenService}: fail-closed enablement, strict
 * per-document binding, uniform null on every failure mode, and TTL clamping.
 */
class DownloadTokenServiceTest {

    private static final String STRONG_SECRET = "0123456789abcdef0123456789abcdef-more-entropy";
    private static final String MINTER = "user@example.com";

    private final UUID documentId = UUID.randomUUID();

    private static DownloadTokenService service(DownloadTokenProperties properties) {
        return new DownloadTokenService(properties);
    }

    private static DownloadTokenProperties enabledProperties() {
        DownloadTokenProperties properties = new DownloadTokenProperties();
        properties.setEnabled(true);
        properties.setSigningSecret(STRONG_SECRET);
        return properties;
    }

    @Test
    @DisplayName("off by default: no token is ever minted or accepted")
    void disabledByDefault() {
        DownloadTokenService service = service(new DownloadTokenProperties());
        assertThat(service.isEnabled()).isFalse();
        assertThat(service.mint(documentId, MINTER)).isNull();
    }

    @Test
    @DisplayName("enabled without a secret, or with a short one, stays OFF — no padded fallback")
    void refusesWeakOrMissingSecret() {
        DownloadTokenProperties noSecret = new DownloadTokenProperties();
        noSecret.setEnabled(true);
        assertThat(service(noSecret).isEnabled()).isFalse();

        DownloadTokenProperties shortSecret = new DownloadTokenProperties();
        shortSecret.setEnabled(true);
        shortSecret.setSigningSecret("too-short");
        assertThat(service(shortSecret).isEnabled()).isFalse();
        assertThat(service(shortSecret).mint(documentId, MINTER)).isNull();
    }

    @Test
    @DisplayName("mint/validate round-trip returns the minter, bound to exactly that document")
    void roundTrip() {
        DownloadTokenService service = service(enabledProperties());
        String token = service.mint(documentId, MINTER);
        assertThat(token).isNotNull();
        assertThat(service.validate(token, documentId)).isEqualTo(MINTER);
        // Same valid token, different document: rejected — one token, one file
        assertThat(service.validate(token, UUID.randomUUID())).isNull();
    }

    @Test
    @DisplayName("no identity, no token: an anonymous mint request yields nothing")
    void refusesAnonymousMint() {
        DownloadTokenService service = service(enabledProperties());
        assertThat(service.mint(documentId, null)).isNull();
        assertThat(service.mint(documentId, " ")).isNull();
        assertThat(service.mint(null, MINTER)).isNull();
    }

    @Test
    @DisplayName("a tampered or foreign-signed token is rejected")
    void refusesTamperedToken() {
        DownloadTokenService service = service(enabledProperties());
        String token = service.mint(documentId, MINTER);
        assertThat(service.validate(token + "x", documentId)).isNull();
        assertThat(service.validate("not-a-jwt", documentId)).isNull();

        DownloadTokenProperties otherKey = enabledProperties();
        otherKey.setSigningSecret(STRONG_SECRET + "-different-key-material");
        assertThat(service(otherKey).validate(token, documentId)).isNull();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void refusesExpiredToken() {
        DownloadTokenProperties properties = enabledProperties();
        properties.setDefaultTtlSeconds(-60);
        String token = service(properties).mint(documentId, MINTER);
        assertThat(token).isNotNull();
        assertThat(service(properties).validate(token, documentId)).isNull();
    }

    @Test
    @DisplayName("the TTL is clamped to the configured hard cap")
    void ttlIsClamped() {
        DownloadTokenProperties properties = enabledProperties();
        properties.setDefaultTtlSeconds(999_999);
        properties.setMaxTtlSeconds(900);
        assertThat(service(properties).ttlSeconds()).isEqualTo(900);
    }

    @Test
    @DisplayName("a token minted while enabled dies with the feature switch")
    void disablingKillsOutstandingTokens() {
        DownloadTokenProperties properties = enabledProperties();
        DownloadTokenService service = service(properties);
        String token = service.mint(documentId, MINTER);
        properties.setEnabled(false);
        assertThat(service.validate(token, documentId)).isNull();
    }
}
