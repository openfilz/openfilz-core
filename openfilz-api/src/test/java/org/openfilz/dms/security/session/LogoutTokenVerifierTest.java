package org.openfilz.dms.security.session;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import reactor.test.StepVerifier;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

class LogoutTokenVerifierTest {

    private static KeyPair realmKey;
    private static KeyPair otherKey;

    @BeforeAll
    static void keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        realmKey = generator.generateKeyPair();
        otherKey = generator.generateKeyPair();
    }

    private static LogoutTokenVerifier verifier() {
        return new LogoutTokenVerifier(NimbusReactiveJwtDecoder.withPublicKey((RSAPublicKey) realmKey.getPublic()).build());
    }

    private static String sign(KeyPair key, JWTClaimsSet claims) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(new JOSEObjectType("logout+jwt")).build(), claims);
        jwt.sign(new RSASSASigner(key.getPrivate()));
        return jwt.serialize();
    }

    private static JWTClaimsSet.Builder logoutClaims(String sid) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .issuer("http://keycloak/realms/openfilz")
                .audience("openfilz-web")
                .subject(UUID.randomUUID().toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(120)))
                .jwtID(UUID.randomUUID().toString())
                .claim("sid", sid)
                .claim("events", Map.of(LogoutTokenVerifier.BACKCHANNEL_LOGOUT_EVENT, Map.of()));
    }

    @Test
    void aLogoutTokenSignedByTheRealmGivesItsSession() throws Exception {
        StepVerifier.create(verifier().verify(sign(realmKey, logoutClaims("session-1").build())))
                .expectNext("session-1")
                .verifyComplete();
    }

    @Test
    void aTokenSignedByAnotherKeyIsRefused() throws Exception {
        StepVerifier.create(verifier().verify(sign(otherKey, logoutClaims("session-1").build())))
                .expectError()
                .verify();
    }

    @Test
    void aTokenWithoutTheLogoutEventIsRefused() throws Exception {
        // An access or ID token, signed by the same realm key, must not sign anyone out.
        JWTClaimsSet claims = logoutClaims("session-1").claim("events", null).build();
        StepVerifier.create(verifier().verify(sign(realmKey, claims))).expectError().verify();
    }

    @Test
    void aTokenWithANonceIsRefused() throws Exception {
        JWTClaimsSet claims = logoutClaims("session-1").claim("nonce", "n").build();
        StepVerifier.create(verifier().verify(sign(realmKey, claims))).expectError().verify();
    }

    @Test
    void aTokenWithoutSessionIsRefused() throws Exception {
        JWTClaimsSet claims = logoutClaims(null).build();
        StepVerifier.create(verifier().verify(sign(realmKey, claims))).expectError().verify();
    }

    @Test
    void anExpiredTokenIsRefused() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        JWTClaimsSet claims = logoutClaims("session-1")
                .issueTime(Date.from(past)).expirationTime(Date.from(past.plusSeconds(60))).build();
        StepVerifier.create(verifier().verify(sign(realmKey, claims))).expectError().verify();
    }

    @Test
    void withoutAJwkSetNothingIsAccepted() throws Exception {
        StepVerifier.create(new LogoutTokenVerifier("").verify(sign(realmKey, logoutClaims("s").build())))
                .expectError(IllegalStateException.class)
                .verify();
    }
}
