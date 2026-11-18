package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import org.openfilz.dms.exception.AiKeyUnreadableException;
import org.openfilz.dms.utils.SecretKeyring;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM cipher for user-provided LLM API keys (BYOK), on a {@link SecretKeyring}.
 * <p>
 * The current key comes from {@code openfilz.ai.user-settings.encryption-key} (env
 * {@code AI_SETTINGS_ENCRYPTION_KEY}): base64 of 32 random bytes, e.g. {@code openssl rand -base64 32}.
 * Keys it replaced go in {@code openfilz.ai.user-settings.previous-encryption-keys} (env
 * {@code AI_SETTINGS_ENCRYPTION_PREVIOUS_KEYS}, comma-separated): values stored under them stay readable,
 * and the start-up pass ({@code AiKeyReencryptionRunner}) writes them again under the current key.
 * New values are stored as {@code v2:<kid>:<base64(iv || ciphertext+tag)>}; older values without a
 * prefix are still read.
 * <p>
 * The BYOK feature flag is read at <em>runtime</em> (native-image-safe); this bean always
 * exists when AI is active, but the key is only required when the feature is enabled:
 * enforced by the fail-fast startup guard below, so a misconfigured deployment is caught
 * at boot rather than on the first user save.
 */
@Component
@Lazy
public class AiSettingsCipher {

    static final String KEY_VARIABLE = "AI_SETTINGS_ENCRYPTION_KEY";
    static final String PREVIOUS_KEYS_VARIABLE = "AI_SETTINGS_ENCRYPTION_PREVIOUS_KEYS";

    @Value("${openfilz.ai.user-settings.enabled:false}")
    private boolean userSettingsEnabled;

    @Value("${openfilz.ai.user-settings.encryption-key:}")
    private String encodedKey;

    @Value("${openfilz.ai.user-settings.previous-encryption-keys:}")
    private String previousKeys;

    private SecretKeyring keyring;

    @PostConstruct
    void init() {
        if (encodedKey != null && !encodedKey.isBlank()) {
            try {
                this.keyring = SecretKeyring.of(encodedKey, previousKeys, null, KEY_VARIABLE, PREVIOUS_KEYS_VARIABLE);
            } catch (SecretKeyring.InvalidKeyException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        } else if (!SecretKeyring.split(previousKeys).isEmpty()) {
            throw new IllegalStateException(PREVIOUS_KEYS_VARIABLE + " is set but " + KEY_VARIABLE
                    + " is not: set the new key in " + KEY_VARIABLE + " and keep the old ones in " + PREVIOUS_KEYS_VARIABLE);
        } else if (userSettingsEnabled) {
            throw new IllegalStateException(
                    "openfilz.ai.user-settings.enabled=true requires " + KEY_VARIABLE
                            + " (base64, 32 bytes): generate one with: openssl rand -base64 32");
        }
    }

    public boolean isConfigured() {
        return keyring != null;
    }

    /** Whether previous keys are configured (the start-up re-encryption pass runs only then). */
    public boolean hasPreviousKeys() {
        return keyring != null && keyring.hasPreviousKeys();
    }

    /** Key id of the current key (first 8 hex characters of its SHA-256), for the logs. */
    public String currentKid() {
        requireKey();
        return keyring.currentKid();
    }

    public String encrypt(String plaintext) {
        requireKey();
        return keyring.encrypt(plaintext);
    }

    /**
     * @throws AiKeyUnreadableException when neither the current key nor a previous key opens the value
     */
    public String decrypt(String encoded) {
        requireKey();
        try {
            return keyring.decrypt(encoded);
        } catch (SecretKeyring.UnreadableSecretException e) {
            throw new AiKeyUnreadableException("Failed to decrypt a stored AI API key (" + e.getMessage()
                    + "): was " + KEY_VARIABLE + " changed without listing the old key in " + PREVIOUS_KEYS_VARIABLE + "?");
        }
    }

    /** Whether the value is already stored under the current key. */
    public boolean isCurrent(String encoded) {
        requireKey();
        return keyring.isCurrent(encoded);
    }

    /**
     * The value written again under the current key, or null when it already is.
     *
     * @throws AiKeyUnreadableException when no configured key opens it
     */
    public String reencrypt(String encoded) {
        requireKey();
        if (keyring.isCurrent(encoded)) {
            return null;
        }
        return keyring.encrypt(decrypt(encoded));
    }

    private void requireKey() {
        if (keyring == null) {
            throw new IllegalStateException(KEY_VARIABLE + " is not configured");
        }
    }
}
