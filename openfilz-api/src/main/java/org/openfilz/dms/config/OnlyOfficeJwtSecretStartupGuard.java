package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Refuses to start with an OnlyOffice JWT secret that cannot keep anyone out.
 * <p>
 * That secret signs the document server's calls <em>and</em> every editor access token
 * ({@code {documentId, userId, exp}}): whoever knows it downloads any document through
 * {@code /documents/{id}/onlyoffice-download?token=} and overwrites any document through the save
 * callback. The value shipped in our own examples for years is therefore public knowledge, and a
 * short one is brute-forceable. When OnlyOffice is on and JWT is on, startup fails — before the
 * port opens — if the secret is blank, shorter than {@value #MIN_SECRET_LENGTH} characters, or one
 * of the values published in OpenFilz templates, examples and docs. The message names
 * {@code ONLYOFFICE_JWT_SECRET} and how to generate a good one.
 * <p>
 * Runtime check on the bound properties, never a bean condition: the toggles flip per deployment
 * and the same native image serves them all. An extension layer may replace an unsafe value
 * before this bean sees it (it gets the fully initialised properties bean); what reaches here is
 * what the JWT services will sign with.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnlyOfficeJwtSecretStartupGuard implements InitializingBean {

    static final int MIN_SECRET_LENGTH = 32;

    /** Values shipped in OpenFilz templates, examples and docs — public, so never a secret. */
    static final Set<String> KNOWN_PUBLIC_SECRETS = Set.of(
            "openfilz-onlyoffice-jwt-secret-2024",
            "change_me_onlyoffice_jwt_secret!",
            "change-this-onlyoffice-jwt-secret",
            "your-onlyoffice-jwt-secret",
            "default-secret",
            "secret");

    private final OnlyOfficeProperties properties;

    @Override
    public void afterPropertiesSet() {
        check(properties);
    }

    /**
     * Throws when OnlyOffice + JWT are enabled and the secret is unsafe; a no-op otherwise.
     */
    static void check(OnlyOfficeProperties properties) {
        if (properties == null || !properties.isEnabled()
                || properties.getJwt() == null || !properties.getJwt().isEnabled()) {
            return;
        }
        String reason = rejectionReason(properties.getJwt().getSecret());
        if (reason == null) {
            log.debug("OnlyOffice JWT secret accepted");
            return;
        }
        throw new IllegalStateException(
                "Refusing to start: the OnlyOffice JWT secret (onlyoffice.jwt.secret / ONLYOFFICE_JWT_SECRET) is "
                        + reason + ". That secret signs every editor access token, so anyone who knows it can "
                        + "download or overwrite any document through the OnlyOffice endpoints. Set "
                        + "ONLYOFFICE_JWT_SECRET to a private value of at least " + MIN_SECRET_LENGTH
                        + " characters (for example `openssl rand -hex 32`) and configure the same value as "
                        + "JWT_SECRET on the OnlyOffice document server — or set ONLYOFFICE_ENABLED=false.");
    }

    /**
     * Why this secret is refused, or {@code null} when it is acceptable.
     */
    static String rejectionReason(String secret) {
        if (secret == null || secret.isBlank()) {
            return "not set";
        }
        String normalized = secret.strip().toLowerCase(Locale.ROOT);
        if (KNOWN_PUBLIC_SECRETS.contains(normalized)
                || normalized.startsWith("change_me") || normalized.startsWith("changeme")
                || normalized.startsWith("change-me") || normalized.startsWith("change-this")) {
            return "a publicly known example value";
        }
        if (secret.strip().length() < MIN_SECRET_LENGTH) {
            return "too short (" + secret.strip().length() + " characters, minimum " + MIN_SECRET_LENGTH + ")";
        }
        return null;
    }
}
