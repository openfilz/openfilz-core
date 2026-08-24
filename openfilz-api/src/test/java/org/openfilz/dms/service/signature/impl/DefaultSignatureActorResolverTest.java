package org.openfilz.dms.service.signature.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.service.signature.SignatureActorResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultSignatureActorResolverTest {

    private final DefaultSignatureActorResolver resolver = new DefaultSignatureActorResolver();

    @Test
    void signerAuthentication_carriesRecipientIdentity() {
        SignatureRecipient r = SignatureRecipient.builder()
                .id(UUID.randomUUID()).recipientEmail("signer@x.io").recipientName("Signer Name").build();

        Authentication auth = resolver.signerAuthentication(r);

        assertThat(auth).isInstanceOf(JwtAuthenticationToken.class);
        Jwt jwt = ((JwtAuthenticationToken) auth).getToken();
        assertThat(jwt.getClaimAsString("email")).isEqualTo("signer@x.io");
        assertThat(jwt.getSubject()).isEqualTo("signer@x.io");
        assertThat(jwt.getClaimAsString("preferred_username")).isEqualTo("signer@x.io");
        assertThat(jwt.getClaimAsString("name")).isEqualTo("Signer Name");
        assertThat(jwt.getClaimAsString("azp")).isEqualTo(SignatureActorResolver.AZP_SIGNATURE_LINK);
        assertThat(jwt.getTokenValue()).isEqualTo("synthetic");
        assertThat(jwt.getHeaders()).containsEntry("alg", "none");
        assertThat(jwt.getExpiresAt()).isAfter(Instant.now());
        assertThat(jwt.getClaimAsMap("realm_access")).containsEntry("roles", List.of());
        assertThat(auth.getName()).isEqualTo("signer@x.io");
        assertThat(auth.getAuthorities()).isEmpty();
        assertThat(auth.isAuthenticated()).isTrue();
    }

    @Test
    void signerAuthentication_nameDefaultsToEmail() {
        SignatureRecipient r = SignatureRecipient.builder().recipientEmail("anon@x.io").build();
        Jwt jwt = ((JwtAuthenticationToken) resolver.signerAuthentication(r)).getToken();
        assertThat(jwt.getClaimAsString("name")).isEqualTo("anon@x.io");
    }

    @Test
    void requesterAuthentication_usesInitiatorEmail_andServiceAzp() {
        SignatureEnvelope env = SignatureEnvelope.builder().initiatorEmail("boss@x.io").build();

        StepVerifier.create(resolver.requesterAuthentication(env))
                .assertNext(auth -> {
                    Jwt jwt = ((JwtAuthenticationToken) auth).getToken();
                    assertThat(jwt.getClaimAsString("email")).isEqualTo("boss@x.io");
                    assertThat(jwt.getClaimAsString("name")).isEqualTo("boss@x.io");
                    assertThat(jwt.getClaimAsString("azp")).isEqualTo(SignatureActorResolver.AZP_SIGNATURE_SERVICE);
                    assertThat(auth.getName()).isEqualTo("boss@x.io");
                })
                .verifyComplete();
    }

    @Test
    void synthetic_claimsAreIsolatedPerCall() {
        Jwt a = ((JwtAuthenticationToken) DefaultSignatureActorResolver.synthetic("a@x.io", null, "z")).getToken();
        Jwt b = ((JwtAuthenticationToken) DefaultSignatureActorResolver.synthetic("b@x.io", "B", "z")).getToken();
        assertThat(a.getClaims()).isNotEqualTo(b.getClaims());
        assertThat((Map<String, Object>) a.getClaims()).containsKeys("sub", "email", "preferred_username", "name", "azp", "realm_access");
    }
}
