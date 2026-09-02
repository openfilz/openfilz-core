package org.openfilz.dms.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.DownloadTokenProperties;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Mints and validates the short-lived signed download links {@code downloadDocument} hands out
 * (see {@link DownloadTokenProperties} for why). Scope of a token, by construction:
 * <ul>
 *   <li><b>One document, one action.</b> The claims bind {@code documentId} + a download-only
 *       audience; the token is accepted on exactly one {@code GET} and grants no listing, no
 *       writes, no other document.</li>
 *   <li><b>No escalation.</b> Minting happens only after the role policy and access policy have
 *       passed for the requesting user ({@code DocumentAiTools.fetchDownload}), and the token
 *       carries that user as {@code sub} — redemption runs the normal download flow <em>as</em>
 *       the minter (audit attribution included), so extension layers re-check document access at
 *       click time and revocation takes effect immediately, not at expiry.</li>
 *   <li><b>Unforgeable, expiring.</b> HS256 over an operator-set secret of at least 32 bytes
 *       (feature refuses to run without one), TTL bounded by {@code maxTtlSeconds}.</li>
 * </ul>
 * Every validation failure — bad signature, expired, wrong document, wrong audience — collapses
 * to the same {@code null}, which the security chain turns into a uniform 404: a token is never
 * an oracle for why it failed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DownloadTokenService {

    /** Query parameter carrying the token on the download endpoint. */
    public static final String TOKEN_PARAM = "token";

    private static final int MIN_SECRET_BYTES = 32;
    private static final String CLAIM_DOCUMENT_ID = "docId";

    private final DownloadTokenProperties properties;

    /** So a misconfigured secret is reported once, not on every downloadDocument call. */
    private final AtomicBoolean misconfigurationLogged = new AtomicBoolean();

    /**
     * Whether tokens can be minted and redeemed right now. {@code enabled} alone is not enough:
     * without a strong secret the feature stays off — a padded or defaulted secret would make
     * every issued URL forgeable, so unlike the OnlyOffice JWT there is no fallback.
     */
    public boolean isEnabled() {
        if (!properties.isEnabled()) {
            return false;
        }
        String secret = properties.getSigningSecret();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            if (misconfigurationLogged.compareAndSet(false, true)) {
                log.warn("openfilz.download-tokens.enabled=true but signing-secret is missing or "
                        + "shorter than {} bytes — signed download links stay OFF.", MIN_SECRET_BYTES);
            }
            return false;
        }
        return true;
    }

    /** The effective TTL, clamped to the configured hard cap. */
    public long ttlSeconds() {
        return Math.min(properties.getDefaultTtlSeconds(), properties.getMaxTtlSeconds());
    }

    /**
     * A signed download token for one document, minted for the (already authorized) requesting
     * user — or {@code null} when the feature is off or the minter has no identity: an
     * anonymous token could not be re-authorized or attributed at redemption, so none is issued.
     */
    public String mint(UUID documentId, String minterEmail) {
        if (!isEnabled() || documentId == null || minterEmail == null || minterEmail.isBlank()) {
            return null;
        }
        Instant now = Instant.now();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(minterEmail)
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .claim(CLAIM_DOCUMENT_ID, documentId.toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(ttlSeconds())))
                .signWith(signingKey())
                .compact();
    }

    /**
     * Validate a token for exactly the document named in the request path.
     *
     * @return the minter's email when the token is valid for this document, else {@code null} —
     *         for every failure mode alike
     */
    public String validate(String token, UUID pathDocumentId) {
        if (!isEnabled() || token == null || token.isBlank() || pathDocumentId == null) {
            return null;
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .requireIssuer(properties.getIssuer())
                    .requireAudience(properties.getAudience())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (!pathDocumentId.toString().equals(claims.get(CLAIM_DOCUMENT_ID, String.class))) {
                log.debug("Download token rejected: document mismatch for {}", pathDocumentId);
                return null;
            }
            String minterEmail = claims.getSubject();
            return (minterEmail == null || minterEmail.isBlank()) ? null : minterEmail;
        } catch (Exception e) {
            log.debug("Download token rejected: {}", e.getMessage());
            return null;
        }
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(properties.getSigningSecret().getBytes(StandardCharsets.UTF_8));
    }
}
