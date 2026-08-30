package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.Role;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static java.util.List.of;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * The BYOK settings endpoints ({@code /api/v1/settings/ai**}) are per-user preferences, not
 * document writes: saving a key, testing a connection, listing the provider's models and
 * resetting must all work for a plain READER. Before the dedicated branch in
 * {@link AbstractSecurityService#authorize}, only the GET matched a rule — PUT/POST were denied
 * outright and DELETE demanded CLEANER, so "Test connection" answered 403.
 */
class AiSettingsAuthorizationTest {

    private SecurityServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SecurityServiceImpl(
                new AutorizationMode(),
                mock(OnlyOfficeProperties.class),
                mock(ThumbnailProperties.class));
    }

    private JwtAuthenticationToken user(String... realmRoles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user")
                .claim("email", "user@openfilz.com")
                .claim("realm_access", Map.of("roles", List.of(realmRoles)))
                .build();
        return new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    private boolean authorize(JwtAuthenticationToken auth, HttpMethod method, String path) {
        MockServerHttpRequest request = MockServerHttpRequest.method(method, path).build();
        AuthorizationContext context = new AuthorizationContext(MockServerWebExchange.from(request));
        return service.authorize(auth, context);
    }

    @Test
    void readerMayManageTheirOwnAiSettings() {
        JwtAuthenticationToken reader = user(Role.READER.toString());
        assertTrue(authorize(reader, HttpMethod.GET, "/api/v1/settings/ai"));
        assertTrue(authorize(reader, HttpMethod.PUT, "/api/v1/settings/ai"));
        assertTrue(authorize(reader, HttpMethod.POST, "/api/v1/settings/ai/test"));
        assertTrue(authorize(reader, HttpMethod.POST, "/api/v1/settings/ai/models"));
        assertTrue(authorize(reader, HttpMethod.DELETE, "/api/v1/settings/ai"));
    }

    @Test
    void contributorMayManageTheirOwnAiSettings() {
        JwtAuthenticationToken contributor = user(Role.CONTRIBUTOR.toString());
        assertTrue(authorize(contributor, HttpMethod.PUT, "/api/v1/settings/ai"));
        assertTrue(authorize(contributor, HttpMethod.POST, "/api/v1/settings/ai/test"));
    }

    /** The branch is a pure widening: CLEANER kept the DELETE it had under the generic rule. */
    @Test
    void cleanerKeepsTheResetItAlreadyHad() {
        JwtAuthenticationToken cleaner = user(Role.CLEANER.toString());
        assertTrue(authorize(cleaner, HttpMethod.DELETE, "/api/v1/settings/ai"));
    }

    @Test
    void userWithoutWebAccessIsStillDenied() {
        JwtAuthenticationToken auditorOnly = user(Role.AUDITOR.toString());
        assertFalse(authorize(auditorOnly, HttpMethod.PUT, "/api/v1/settings/ai"));
        assertFalse(authorize(auditorOnly, HttpMethod.POST, "/api/v1/settings/ai/test"));
    }

    /** The branch is scoped to /settings/ai — the rest of /settings keeps its previous rules. */
    @Test
    void otherSettingsWritesAreNotWidened() {
        JwtAuthenticationToken reader = user(Role.READER.toString());
        assertTrue(authorize(reader, HttpMethod.GET, "/api/v1/settings"));
        assertFalse(authorize(reader, HttpMethod.PUT, "/api/v1/settings"));
        assertFalse(authorize(reader, HttpMethod.POST, "/api/v1/settings/aircraft"));
    }
}
