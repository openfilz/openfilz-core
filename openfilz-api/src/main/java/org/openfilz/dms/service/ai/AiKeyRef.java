package org.openfilz.dms.service.ai;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Short, stable, non-reversible references to API keys.
 * <p>
 * Quota is charged per key, so the failover machinery has to tell one key of a provider from
 * another: which key a model was benched under, which key a cached client belongs to, which key
 * a log line is about. Doing that with the key itself would put live credentials into cooldown
 * maps, cache keys and log output, so everything downstream of {@link #of} handles this
 * fingerprint instead — enough to distinguish keys, useless to anyone who reads a log.
 */
public final class AiKeyRef {

    private AiKeyRef() {
    }

    /** Used when a model's key is not known to us — a BYOK client built elsewhere, say. */
    public static final String UNKNOWN = "unknown";

    /** Bytes of SHA-256 kept: 4 bytes (8 hex chars) is far more than enough to separate a handful of keys. */
    private static final int FINGERPRINT_BYTES = 4;

    /**
     * A short fingerprint of an API key, safe to log and to use as a map key.
     * Blank or null input yields {@link #UNKNOWN} rather than a fingerprint of the empty string,
     * so "no key" never collides with a real one.
     */
    public static String of(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return UNKNOWN;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(apiKey.getBytes(StandardCharsets.UTF_8));
            byte[] truncated = new byte[FINGERPRINT_BYTES];
            System.arraycopy(digest, 0, truncated, 0, FINGERPRINT_BYTES);
            return HexFormat.of().formatHex(truncated);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS on every conforming JRE; unreachable in practice.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
