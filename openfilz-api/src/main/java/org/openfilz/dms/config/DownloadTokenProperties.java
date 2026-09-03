package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration for signed download links ({@code openfilz.download-tokens.*}).
 * <p>
 * The AI front-ends' {@code downloadDocument} used to hand back a bare REST URL that requires a
 * bearer <em>header</em> — unusable from a chat transcript or a browser address bar. When this
 * feature is on, the URL instead carries a short-lived HS256 token
 * ({@code ?token=…}) minted for the requesting user and bound to that one document, so the
 * human behind the conversation can simply click it. See {@code DownloadTokenService} for the
 * exact scope of what such a token can and cannot do.
 * <p>
 * Deliberately NOT gated on a bean condition: like {@link McpProperties} the whole feature is
 * toggled at <em>runtime</em> (GraalVM-native safe) — the security chain's matcher and the
 * minting service both read {@code enabled} per call.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.download-tokens")
public class DownloadTokenProperties {

    /** Master runtime switch. Off by default: a capability URL is an explicit opt-in. */
    private boolean enabled = false;

    /**
     * HMAC-SHA256 signing secret, minimum 32 bytes. No default and no padding — operators MUST
     * set one, or the feature stays off even when {@code enabled=true} (a guessable secret would
     * turn every download URL into a forgeable credential).
     */
    private String signingSecret;

    /** Token issuer claim. */
    private String issuer = "openfilz-download-tokens";

    /** Token audience claim — the download endpoint that consumes the token. */
    private String audience = "openfilz-download";

    /**
     * Token lifetime. Short on purpose: the URL transits conversation logs and browser
     * history, and an expired link in either is inert forever after.
     */
    private long defaultTtlSeconds = 300;

    /** Hard upper bound on the TTL, whatever {@code defaultTtlSeconds} is configured to. */
    private long maxTtlSeconds = 900;
}
