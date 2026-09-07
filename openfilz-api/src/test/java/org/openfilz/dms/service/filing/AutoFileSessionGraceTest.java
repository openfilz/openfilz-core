package org.openfilz.dms.service.filing;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;
import org.openfilz.dms.service.ai.DocumentTextHandoff;
import org.openfilz.dms.service.ai.ReorganizationPlanService.Caller;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The grace on the uploader's token. A filing is queued by a request that was authenticated, but
 * it runs later: a batch of hundreds of uploads outlives a five-minute access token, and the raw
 * expiry alone skipped the whole tail of the batch with "the session expired".
 */
class AutoFileSessionGraceTest {

    @Test
    void aLiveTokenIsNeverOver() {
        assertThat(service(Duration.ofMinutes(30)).sessionOver(callerWithTokenExpiring(Instant.now().plusSeconds(120)))).isFalse();
    }

    @Test
    void aTokenExpiredInsideTheGraceStillFiles() {
        assertThat(service(Duration.ofMinutes(30)).sessionOver(callerWithTokenExpiring(Instant.now().minusSeconds(600)))).isFalse();
    }

    @Test
    void aTokenExpiredBeyondTheGraceDoesNot() {
        assertThat(service(Duration.ofMinutes(30)).sessionOver(callerWithTokenExpiring(Instant.now().minusSeconds(3600)))).isTrue();
    }

    @Test
    void zeroGraceKeepsTheOldBehaviour() {
        assertThat(service(Duration.ZERO).sessionOver(callerWithTokenExpiring(Instant.now().minusSeconds(1)))).isTrue();
        assertThat(service(Duration.ZERO).sessionOver(callerWithTokenExpiring(Instant.now().plusSeconds(60)))).isFalse();
    }

    @Test
    void anIdentityWithoutAnExpiringTokenIsNeverOver() {
        DefaultAutoFileService service = service(Duration.ZERO);
        assertThat(service.sessionOver(new Caller("someone@example.com", null))).isFalse();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").claims(c -> c.put("sub", "s")).build();
        assertThat(service.sessionOver(new Caller("someone@example.com",
                new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES)))).isFalse();
    }

    private static DefaultAutoFileService service(Duration grace) {
        AiProperties properties = new AiProperties();
        properties.getAutoFile().setSessionGrace(grace);
        return new DefaultAutoFileService(properties, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, new DocumentTextHandoff(properties));
    }

    private static Caller callerWithTokenExpiring(Instant expiresAt) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .claims(claims -> claims.putAll(Map.of("sub", "subject", "email", "someone@example.com")))
                .issuedAt(expiresAt.minusSeconds(300))
                .expiresAt(expiresAt)
                .build();
        return new Caller("someone@example.com", new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }
}
