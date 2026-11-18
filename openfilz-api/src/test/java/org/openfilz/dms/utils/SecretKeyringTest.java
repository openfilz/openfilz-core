package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class SecretKeyringTest {

    private static final byte[] AAD = "test-aad".getBytes(StandardCharsets.UTF_8);

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    /** A value in the format written before key ids: base64(iv || ciphertext+tag), no prefix. */
    static String legacy(String key, String plaintext, byte[] aad) throws Exception {
        byte[] iv = new byte[12];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(Base64.getDecoder().decode(key), "AES"), new GCMParameterSpec(128, iv));
        if (aad != null) {
            cipher.updateAAD(aad);
        }
        byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.getEncoder().encodeToString(out);
    }

    private static SecretKeyring ring(String current, String previous) {
        return SecretKeyring.of(current, previous, AAD, "KEY", "PREVIOUS");
    }

    @Test
    void v2RoundTrip_carriesTheKid() {
        String key = randomKey();
        SecretKeyring ring = ring(key, null);

        String stored = ring.encrypt("sk-secret-value");

        String kid = SecretKeyring.kidOf(Base64.getDecoder().decode(key));
        assertEquals(8, kid.length());
        assertTrue(stored.startsWith("v2:" + kid + ":"), stored);
        assertEquals(kid, ring.currentKid());
        assertTrue(ring.isCurrent(stored));
        assertEquals("sk-secret-value", ring.decrypt(stored));
        assertNull(ring.reencrypt(stored));
    }

    @Test
    void v2UnderAPreviousKey_isReadAndRewritten() {
        String oldKey = randomKey();
        String newKey = randomKey();
        String stored = ring(oldKey, null).encrypt("secret");

        SecretKeyring rotated = ring(newKey, oldKey);

        assertTrue(rotated.hasPreviousKeys());
        assertFalse(rotated.isCurrent(stored));
        assertEquals("secret", rotated.decrypt(stored));
        String rewritten = rotated.reencrypt(stored);
        assertTrue(rotated.isCurrent(rewritten));
        assertEquals("secret", ring(newKey, null).decrypt(rewritten));
    }

    @Test
    void oldFormat_readWithTheCurrentKey() throws Exception {
        String key = randomKey();
        String stored = legacy(key, "secret", AAD);

        SecretKeyring ring = ring(key, null);

        assertFalse(ring.isCurrent(stored));
        assertEquals("secret", ring.decrypt(stored));
        assertTrue(ring.isCurrent(ring.reencrypt(stored)));
    }

    @Test
    void oldFormat_readWithAPreviousKey() throws Exception {
        String oldKey = randomKey();
        String stored = legacy(oldKey, "secret", AAD);

        SecretKeyring ring = ring(randomKey(), randomKey() + "," + oldKey);

        assertEquals("secret", ring.decrypt(stored));
    }

    @Test
    void unknownKid_isUnreadable() {
        String stored = ring(randomKey(), null).encrypt("secret");

        SecretKeyring other = ring(randomKey(), randomKey());

        SecretKeyring.UnreadableSecretException e = assertThrows(SecretKeyring.UnreadableSecretException.class,
                () -> other.decrypt(stored));
        assertTrue(e.getMessage().contains("neither the current key nor a previous key"));
        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> other.reencrypt(stored));
    }

    @Test
    void oldFormatUnderNoKnownKey_isUnreadable() throws Exception {
        String stored = legacy(randomKey(), "secret", AAD);

        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> ring(randomKey(), randomKey()).decrypt(stored));
    }

    @Test
    void tamperedOrMalformed_isUnreadable() {
        String key = randomKey();
        SecretKeyring ring = ring(key, null);
        String stored = ring.encrypt("secret");
        String body = stored.substring(stored.lastIndexOf(':') + 1);
        byte[] bytes = Base64.getDecoder().decode(body);
        bytes[bytes.length - 1] ^= 1;
        String tampered = stored.substring(0, stored.lastIndexOf(':') + 1) + Base64.getEncoder().encodeToString(bytes);

        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> ring.decrypt(tampered));
        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> ring.decrypt("v2:nocolon"));
        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> ring.decrypt("not base64!!"));
        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> ring.decrypt(""));
    }

    @Test
    void associatedData_bindsAValueToItsRing() {
        String key = randomKey();
        String stored = SecretKeyring.of(key, null, "one".getBytes(StandardCharsets.UTF_8), "K", "P").encrypt("secret");

        SecretKeyring sameKeyOtherUse = SecretKeyring.of(key, null, "two".getBytes(StandardCharsets.UTF_8), "K", "P");

        assertThrows(SecretKeyring.UnreadableSecretException.class, () -> sameKeyOtherUse.decrypt(stored));
    }

    @Test
    void currentKeyListedAsPrevious_isNotAPreviousKey() {
        String key = randomKey();

        assertFalse(ring(key, " " + key + " , ").hasPreviousKeys());
    }

    @Test
    void invalidKeys_nameTheVariable() {
        assertTrue(assertThrows(SecretKeyring.InvalidKeyException.class, () -> ring("", null)).getMessage().contains("KEY"));
        assertTrue(assertThrows(SecretKeyring.InvalidKeyException.class, () -> ring("not-base64!!", null))
                .getMessage().contains("KEY"));
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
        SecretKeyring.InvalidKeyException e = assertThrows(SecretKeyring.InvalidKeyException.class,
                () -> ring(randomKey(), randomKey() + "," + shortKey));
        assertTrue(e.getMessage().contains("PREVIOUS (entry 2)"), e.getMessage());
        assertFalse(e.getMessage().contains(shortKey));
    }
}
