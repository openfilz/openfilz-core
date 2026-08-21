package org.openfilz.dms.service.impl;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM cipher for user-provided LLM API keys (BYOK).
 * <p>
 * The master key comes from {@code openfilz.ai.user-settings.encryption-key}
 * (env {@code AI_SETTINGS_ENCRYPTION_KEY}): base64 of 32 random bytes, e.g.
 * {@code openssl rand -base64 32}. Stored value layout: base64(iv[12] || ciphertext+tag).
 * <p>
 * The BYOK feature flag is read at <em>runtime</em> (native-image-safe); this bean always
 * exists when AI is active, but the key is only required when the feature is enabled —
 * enforced by the fail-fast startup guard below, so a misconfigured deployment is caught
 * at boot rather than on the first user save.
 */
@Component
@Lazy
public class AiSettingsCipher {

    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${openfilz.ai.user-settings.enabled:false}")
    private boolean userSettingsEnabled;

    @Value("${openfilz.ai.user-settings.encryption-key:}")
    private String encodedKey;

    private SecretKeySpec key;

    @PostConstruct
    void init() {
        if (encodedKey != null && !encodedKey.isBlank()) {
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(encodedKey.trim());
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException(
                        "AI_SETTINGS_ENCRYPTION_KEY is not valid base64 — generate one with: openssl rand -base64 32", e);
            }
            if (keyBytes.length != 32) {
                throw new IllegalStateException(
                        "AI_SETTINGS_ENCRYPTION_KEY must decode to 32 bytes (got " + keyBytes.length
                                + ") — generate one with: openssl rand -base64 32");
            }
            this.key = new SecretKeySpec(keyBytes, "AES");
        } else if (userSettingsEnabled) {
            throw new IllegalStateException(
                    "openfilz.ai.user-settings.enabled=true requires AI_SETTINGS_ENCRYPTION_KEY "
                            + "(base64, 32 bytes) — generate one with: openssl rand -base64 32");
        }
    }

    public boolean isConfigured() {
        return key != null;
    }

    public String encrypt(String plaintext) {
        requireKey();
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt AI settings API key", e);
        }
    }

    public String decrypt(String encoded) {
        requireKey();
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(GCM_TAG_LENGTH_BITS, in, 0, GCM_IV_LENGTH));
            byte[] plaintext = cipher.doFinal(in, GCM_IV_LENGTH, in.length - GCM_IV_LENGTH);
            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to decrypt AI settings API key — was AI_SETTINGS_ENCRYPTION_KEY changed?", e);
        }
    }

    private void requireKey() {
        if (key == null) {
            throw new IllegalStateException("AI_SETTINGS_ENCRYPTION_KEY is not configured");
        }
    }
}
