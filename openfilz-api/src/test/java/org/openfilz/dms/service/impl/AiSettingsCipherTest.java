package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link AiSettingsCipher} (AES-256-GCM for BYOK API keys).
 */
class AiSettingsCipherTest {

    private static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    private static AiSettingsCipher cipher(String encodedKey, boolean enabled) {
        AiSettingsCipher cipher = new AiSettingsCipher();
        ReflectionTestUtils.setField(cipher, "encodedKey", encodedKey);
        ReflectionTestUtils.setField(cipher, "userSettingsEnabled", enabled);
        ReflectionTestUtils.invokeMethod(cipher, "init");
        return cipher;
    }

    @Test
    void encryptDecrypt_roundTrips() {
        AiSettingsCipher cipher = cipher(randomKey(), true);

        String secret = "sk-ant-api03-averylongsecretkey";
        String encrypted = cipher.encrypt(secret);

        assertNotEquals(secret, encrypted);
        assertEquals(secret, cipher.decrypt(encrypted));
    }

    @Test
    void encrypt_usesFreshIvPerCall() {
        AiSettingsCipher cipher = cipher(randomKey(), true);

        assertNotEquals(cipher.encrypt("same-plaintext"), cipher.encrypt("same-plaintext"));
    }

    @Test
    void decrypt_tamperedCiphertext_throws() {
        AiSettingsCipher cipher = cipher(randomKey(), true);

        byte[] bytes = Base64.getDecoder().decode(cipher.encrypt("secret"));
        bytes[bytes.length - 1] ^= 0x01; // flip a bit in the GCM tag
        String tampered = Base64.getEncoder().encodeToString(bytes);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void decrypt_withDifferentKey_throws() {
        String encrypted = cipher(randomKey(), true).encrypt("secret");

        assertThrows(IllegalStateException.class, () -> cipher(randomKey(), true).decrypt(encrypted));
    }

    @Test
    void init_enabledWithoutKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> cipher("", true));
    }

    @Test
    void init_disabledWithoutKey_isAllowed() {
        AiSettingsCipher cipher = cipher("", false);

        assertFalse(cipher.isConfigured());
        assertThrows(IllegalStateException.class, () -> cipher.encrypt("secret"));
    }

    @Test
    void init_invalidBase64_failsFast() {
        assertThrows(IllegalStateException.class, () -> cipher("not-base64!!", true));
    }

    @Test
    void init_wrongKeyLength_failsFast() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertThrows(IllegalStateException.class, () -> cipher(shortKey, true));
    }
}
