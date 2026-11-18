package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.exception.AiKeyUnreadableException;
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
        return cipher(encodedKey, "", enabled);
    }

    private static AiSettingsCipher cipher(String encodedKey, String previousKeys, boolean enabled) {
        AiSettingsCipher cipher = new AiSettingsCipher();
        ReflectionTestUtils.setField(cipher, "encodedKey", encodedKey);
        ReflectionTestUtils.setField(cipher, "previousKeys", previousKeys);
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

        String stored = cipher.encrypt("secret");
        String prefix = stored.substring(0, stored.lastIndexOf(':') + 1); // v2:<kid>:
        byte[] bytes = Base64.getDecoder().decode(stored.substring(prefix.length()));
        bytes[bytes.length - 1] ^= 0x01; // flip a bit in the GCM tag
        String tampered = prefix + Base64.getEncoder().encodeToString(bytes);

        assertThrows(IllegalStateException.class, () -> cipher.decrypt(tampered));
    }

    @Test
    void decrypt_withDifferentKey_throws() {
        String encrypted = cipher(randomKey(), true).encrypt("secret");

        assertThrows(AiKeyUnreadableException.class, () -> cipher(randomKey(), true).decrypt(encrypted));
    }

    @Test
    void encrypt_writesTheV2FormatWithTheKid() {
        AiSettingsCipher cipher = cipher(randomKey(), true);

        String encrypted = cipher.encrypt("secret");

        assertTrue(encrypted.startsWith("v2:" + cipher.currentKid() + ":"), encrypted);
        assertTrue(cipher.isCurrent(encrypted));
        assertNull(cipher.reencrypt(encrypted));
    }

    @Test
    void rotation_previousKeyStillReads_andReencryptMovesTheValue() {
        String oldKey = randomKey();
        String newKey = randomKey();
        String stored = cipher(oldKey, true).encrypt("secret");

        AiSettingsCipher rotated = cipher(newKey, oldKey, true);

        assertTrue(rotated.hasPreviousKeys());
        assertEquals("secret", rotated.decrypt(stored));
        String rewritten = rotated.reencrypt(stored);
        assertTrue(rotated.isCurrent(rewritten));
        assertEquals("secret", cipher(newKey, true).decrypt(rewritten));
    }

    @Test
    void rotation_withoutThePreviousKey_isATypedUnreadableError() {
        String stored = cipher(randomKey(), true).encrypt("secret");
        AiSettingsCipher rotated = cipher(randomKey(), randomKey(), true);

        AiKeyUnreadableException e = assertThrows(AiKeyUnreadableException.class, () -> rotated.decrypt(stored));
        assertTrue(e.getMessage().contains("AI_SETTINGS_ENCRYPTION_PREVIOUS_KEYS"));
        assertThrows(AiKeyUnreadableException.class, () -> rotated.reencrypt(stored));
    }

    @Test
    void init_previousKeysWithoutCurrentKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> cipher("", randomKey(), false));
    }

    @Test
    void init_invalidPreviousKey_failsFast() {
        assertThrows(IllegalStateException.class, () -> cipher(randomKey(), "not-base64!!", true));
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
