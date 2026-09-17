package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.security.WormPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * WORM as a composable rule on {@link AbstractSecurityService} (D1 points 1 & 2), replacing the
 * former {@code WormSecurityServiceImpl} bean.
 * <p>
 * Two properties matter. First, the Community verdict is unchanged: reads and the creation
 * whitelist pass, every other mutation is refused. Second — and this is what the dedicated bean
 * could never do — the rule is inherited, so an edition that authorises writes of its own through
 * {@link AbstractSecurityService#isCustomAccessAuthorized} still cannot write under WORM. That is
 * simulated here by {@link PermissiveEdition}, which stands in for the Enterprise service.
 */
class WormAuthorizationTest {

    /** Global perimeter, switchable per test. */
    private static WormPolicy worm(boolean enforced) {
        return () -> enforced;
    }

    /** An edition whose custom branch authorises everything the core leaves undecided. */
    private static class PermissiveEdition extends AbstractSecurityService {
        PermissiveEdition(WormPolicy wormPolicy) {
            super(new AutorizationMode(), mock(OnlyOfficeProperties.class), mock(ThumbnailProperties.class), wormPolicy);
        }

        @Override
        protected boolean isCustomAccessAuthorized(Authentication auth, AuthorizationContext context,
                                                   HttpMethod method, String path) {
            return true;
        }
    }

    private static SecurityServiceImpl community(boolean wormEnforced) {
        return new SecurityServiceImpl(new AutorizationMode(), mock(OnlyOfficeProperties.class),
                mock(ThumbnailProperties.class), worm(wormEnforced));
    }

    private static JwtAuthenticationToken user(String... realmRoles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user")
                .claim("email", "user@openfilz.com")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private static boolean authorize(AbstractSecurityService service, JwtAuthenticationToken auth,
                                     HttpMethod method, String path) {
        MockServerHttpRequest request = MockServerHttpRequest.method(method, path).build();
        return service.authorize(auth, new AuthorizationContext(MockServerWebExchange.from(request)));
    }

    // ---------------------------------------------------------------- WORM on, Community edition

    @Test
    void wormRefusesEveryDelete() {
        SecurityServiceImpl service = community(true);
        JwtAuthenticationToken cleaner = user(Role.CLEANER.toString());
        assertFalse(authorize(service, cleaner, HttpMethod.DELETE, "/api/v1/documents/1"));
        assertFalse(authorize(service, cleaner, HttpMethod.DELETE, "/api/v1/folders/1"));
    }

    @Test
    void wormRefusesInPlaceMutations() {
        SecurityServiceImpl service = community(true);
        JwtAuthenticationToken contributor = user(Role.CONTRIBUTOR.toString());
        assertFalse(authorize(service, contributor, HttpMethod.PATCH, "/api/v1/documents/1/metadata"));
        assertFalse(authorize(service, contributor, HttpMethod.PUT, "/api/v1/documents/1/metadata"));
        assertFalse(authorize(service, contributor, HttpMethod.POST, "/api/v1/documents/rename"));
        assertFalse(authorize(service, contributor, HttpMethod.POST, "/api/v1/folders/move"));
        assertFalse(authorize(service, contributor, HttpMethod.POST,
                "/api/v1/documents/1/versions/2/restore"));
    }

    @Test
    void wormStillAllowsNewContent() {
        SecurityServiceImpl service = community(true);
        JwtAuthenticationToken contributor = user(Role.CONTRIBUTOR.toString());
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/documents/upload"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/documents/upload-multiple"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/files/copy"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/folders"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/folders/copy"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/pdf/merge"));
    }

    @Test
    void wormLeavesReadsAlone() {
        SecurityServiceImpl service = community(true);
        JwtAuthenticationToken reader = user(Role.READER.toString());
        assertTrue(authorize(service, reader, HttpMethod.GET, "/api/v1/documents/1"));
        assertTrue(authorize(service, reader, HttpMethod.POST, "/api/v1/documents/download-multiple"));
        assertTrue(authorize(service, reader, HttpMethod.POST, "/api/v1/folders/list"));
        assertTrue(authorize(service, reader, HttpMethod.PUT, "/api/v1/favorites"));
        assertTrue(authorize(service, user(Role.AUDITOR.toString()), HttpMethod.GET, "/api/v1/audit/verify"));
    }

    // --------------------------------------------------------------------- WORM off: no change

    @Test
    void withoutWormTheOrdinaryRulesApply() {
        SecurityServiceImpl service = community(false);
        assertTrue(authorize(service, user(Role.CLEANER.toString()), HttpMethod.DELETE, "/api/v1/documents/1"));
        assertTrue(authorize(service, user(Role.CONTRIBUTOR.toString()), HttpMethod.PATCH,
                "/api/v1/documents/1/metadata"));
        assertTrue(authorize(service, user(Role.CONTRIBUTOR.toString()), HttpMethod.POST,
                "/api/v1/documents/1/versions/2/restore"));
    }

    // ------------------------------------------------------- WORM on, edition with custom writes

    @Test
    void wormOutranksAnEditionsOwnWriteRules() {
        PermissiveEdition service = new PermissiveEdition(worm(true));
        JwtAuthenticationToken contributor = user(Role.CONTRIBUTOR.toString());
        // Paths the core knows nothing about, authorised by the edition's custom branch.
        assertFalse(authorize(service, contributor, HttpMethod.POST, "/api/v1/documents/comments"));
        assertFalse(authorize(service, contributor, HttpMethod.DELETE, "/api/v1/documents/comments/1"));
        assertFalse(authorize(service, contributor, HttpMethod.POST, "/api/v1/users/share"));
        assertFalse(authorize(service, contributor, HttpMethod.PUT, "/api/v1/settings/notifications"));
    }

    @Test
    void anEditionKeepsItsOwnWriteRulesWithoutWorm() {
        PermissiveEdition service = new PermissiveEdition(worm(false));
        JwtAuthenticationToken contributor = user(Role.CONTRIBUTOR.toString());
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/documents/comments"));
        assertTrue(authorize(service, contributor, HttpMethod.POST, "/api/v1/users/share"));
    }
}
