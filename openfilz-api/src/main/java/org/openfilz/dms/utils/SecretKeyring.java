package org.openfilz.dms.utils;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AES-256-GCM encryption of small stored secrets under a current key plus previous keys, so a key can be
 * rotated without losing what was stored under the old one.
 * <p>
 * Written values look like {@code v2:<kid>:<base64(iv[12] || ciphertext+tag)>}, where {@code kid} is the
 * first 8 hex characters of the SHA-256 of the raw key. Values without the {@code v2:} prefix are the
 * older format, plain {@code base64(iv || ciphertext+tag)} with no key id (plain base64 never contains
 * {@code :}); they are tried with the current key, then each previous key in order.
 * <p>
 * Each user of this class keeps its own keys and its own optional associated data: the class is shared,
 * never the key. No Spring, no state beyond the keys: thread-safe.
 */
public final class SecretKeyring {

    public static final String PREFIX = "v2:";

    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** A stored value that no key of the ring opens. */
    public static class UnreadableSecretException extends IllegalStateException {
        public UnreadableSecretException(String message) {
            super(message);
        }
    }

    /** A key variable that is not base64 of 32 bytes. */
    public static class InvalidKeyException extends IllegalArgumentException {
        public InvalidKeyException(String message) {
            super(message);
        }
    }

    private record Key(String kid, SecretKeySpec spec) {
    }

    private final Key current;
    /** Previous keys by kid, in the order given, without the current key. */
    private final Map<String, Key> previous;
    private final byte[] aad;
    private final SecureRandom random = new SecureRandom();

    private SecretKeyring(Key current, Map<String, Key> previous, byte[] aad) {
        this.current = current;
        this.previous = previous;
        this.aad = aad == null ? null : aad.clone();
    }

    /**
     * @param currentKey   base64 of 32 bytes (required)
     * @param previousKeys comma-separated list of the same format, may be null or blank
     * @param aad          associated data bound to every value, or null for none
     * @param variable     name of the current-key variable, for the messages
     * @param previousVariable name of the previous-keys variable, for the messages
     * @throws InvalidKeyException when a key is missing or malformed (the message names the variable, never the key)
     */
    public static SecretKeyring of(String currentKey, String previousKeys, byte[] aad,
                                   String variable, String previousVariable) {
        if (currentKey == null || currentKey.isBlank()) {
            throw new InvalidKeyException(variable + " is not set");
        }
        Key current = key(currentKey, variable);
        Map<String, Key> previous = new LinkedHashMap<>();
        int position = 0;
        for (String raw : split(previousKeys)) {
            position++;
            Key key = key(raw, previousVariable + " (entry " + position + ")");
            if (!key.kid().equals(current.kid())) {
                previous.putIfAbsent(key.kid(), key);
            }
        }
        return new SecretKeyring(current, previous, aad);
    }

    /** The non-blank entries of a comma-separated list. */
    public static List<String> split(String list) {
        List<String> out = new ArrayList<>();
        if (list == null) {
            return out;
        }
        for (String part : list.split(",")) {
            if (!part.isBlank()) {
                out.add(part.trim());
            }
        }
        return out;
    }

    /** Key id of a base64 key: first 8 hex characters of the SHA-256 of its raw bytes. */
    public static String kidOf(byte[] rawKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(rawKey);
            return HexFormat.of().formatHex(digest, 0, 4);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static Key key(String encoded, String variable) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyException(variable + " is not valid base64: generate one with openssl rand -base64 32");
        }
        if (bytes.length != KEY_BYTES) {
            throw new InvalidKeyException(variable + " must decode to 32 bytes (got " + bytes.length
                    + "): generate one with openssl rand -base64 32");
        }
        return new Key(kidOf(bytes), new SecretKeySpec(bytes, "AES"));
    }

    public String currentKid() {
        return current.kid();
    }

    public boolean hasPreviousKeys() {
        return !previous.isEmpty();
    }

    /** {@code v2:<current kid>:<base64(iv || ciphertext+tag)>}. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, current.spec(), new GCMParameterSpec(TAG_BITS, iv));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[IV_LENGTH + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, IV_LENGTH);
            System.arraycopy(ciphertext, 0, out, IV_LENGTH, ciphertext.length);
            return PREFIX + current.kid() + ":" + Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the secret", e);
        }
    }

    /**
     * @throws UnreadableSecretException when no key of the ring opens the value (unknown kid, wrong key,
     *                                   tampered or malformed value)
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isEmpty()) {
            throw new UnreadableSecretException("The stored secret is empty");
        }
        if (stored.startsWith(PREFIX)) {
            int colon = stored.indexOf(':', PREFIX.length());
            if (colon < 0) {
                throw new UnreadableSecretException("The stored secret is malformed");
            }
            String kid = stored.substring(PREFIX.length(), colon);
            Key key = current.kid().equals(kid) ? current : previous.get(kid);
            if (key == null) {
                throw new UnreadableSecretException("The stored secret was written under key " + kid
                        + ", which is neither the current key nor a previous key");
            }
            String plaintext = open(key, stored.substring(colon + 1));
            if (plaintext == null) {
                throw new UnreadableSecretException("The stored secret does not open under key " + kid);
            }
            return plaintext;
        }
        String plaintext = open(current, stored);
        if (plaintext != null) {
            return plaintext;
        }
        for (Key key : previous.values()) {
            plaintext = open(key, stored);
            if (plaintext != null) {
                return plaintext;
            }
        }
        throw new UnreadableSecretException("The stored secret does not open under the current key or any previous key");
    }

    /** Whether the value is already in the {@code v2} format under the current key. */
    public boolean isCurrent(String stored) {
        return stored != null && stored.startsWith(PREFIX + current.kid() + ":");
    }

    /**
     * The value written again under the current key, or null when it already is.
     *
     * @throws UnreadableSecretException when no key opens it
     */
    public String reencrypt(String stored) {
        if (isCurrent(stored)) {
            return null;
        }
        return encrypt(decrypt(stored));
    }

    /** The plaintext, or null when this key does not open the value. */
    private String open(Key key, String encoded) {
        try {
            byte[] in = Base64.getDecoder().decode(encoded);
            if (in.length <= IV_LENGTH) {
                return null;
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key.spec(), new GCMParameterSpec(TAG_BITS, in, 0, IV_LENGTH));
            if (aad != null) {
                cipher.updateAAD(aad);
            }
            return new String(cipher.doFinal(in, IV_LENGTH, in.length - IV_LENGTH), StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            return null;
        }
    }
}
