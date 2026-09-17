package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.security.SecurityService;
import org.openfilz.dms.security.WormPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WORM must hold at the tool layer too.
 * <p>
 * The AI chat assistant and the MCP server call {@code DocumentService} in-process on a tool
 * thread: no request is ever matched, so {@code AbstractSecurityService.authorize} — where the
 * WORM prohibition lives — never runs. Without this check a deployment could advertise a
 * write-once repository and still let an agent delete a document through {@code tools/call},
 * exactly as a READER once created folders that REST refused it (the hole
 * {@link ToolCapability} was introduced for).
 * <p>
 * The verdict here is capability-grained, not tool-grained: under WORM every mutating capability
 * is refused, including the creations REST still admits ({@code writeFile}, {@code createFolder}).
 * That is deliberate — fail closed, and an archive is not a thing an autonomous agent fills.
 */
class WormToolPolicyTest {

    private static DefaultAiToolRolePolicy policy(boolean wormEnforced) {
        SecurityService security = mock(SecurityService.class);
        when(security.isAuthorized(any(JwtAuthenticationToken.class), anyString())).thenReturn(true);
        when(security.isAuthorized(any(JwtAuthenticationToken.class), anyList())).thenReturn(true);
        WormPolicy worm = () -> wormEnforced;
        return new DefaultAiToolRolePolicy(Optional.of(security), new SignatureProperties(), worm);
    }

    private static JwtAuthenticationToken privilegedUser() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user")
                .claim("email", "user@openfilz.com")
                .claim("realm_access", Map.of("roles", List.of(
                        Role.CONTRIBUTOR.toString(), Role.CLEANER.toString(), Role.READER.toString())))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_USER")));
    }

    @Test
    void wormRefusesEveryMutatingCapability() {
        DefaultAiToolRolePolicy policy = policy(true);
        JwtAuthenticationToken user = privilegedUser();
        for (ToolCapability capability : ToolCapability.values()) {
            if (capability.isMutating()) {
                assertFalse(policy.isAllowed(user, capability),
                        capability + " must be refused under WORM");
            }
        }
    }

    @Test
    void wormLeavesReadCapabilitiesAlone() {
        DefaultAiToolRolePolicy policy = policy(true);
        JwtAuthenticationToken user = privilegedUser();
        assertTrue(policy.isAllowed(user, ToolCapability.DOCUMENT_READ));
        assertTrue(policy.isAllowed(user, ToolCapability.IDENTITY_READ));
        assertTrue(policy.isAllowed(user, ToolCapability.AUDIT_READ));
    }

    @Test
    void withoutWormMutatingCapabilitiesFollowTheRolesAlone() {
        DefaultAiToolRolePolicy policy = policy(false);
        JwtAuthenticationToken user = privilegedUser();
        assertTrue(policy.isAllowed(user, ToolCapability.DOCUMENT_WRITE));
        assertTrue(policy.isAllowed(user, ToolCapability.DOCUMENT_DELETE));
    }

    /**
     * The unauthenticated template instance short-circuits before any role decision, and must keep
     * doing so — {@code tools/list} is built from it at startup, and a WORM deployment still
     * advertises its tool surface (the calls are refused, the catalogue is not hidden).
     */
    @Test
    void theDefinitionsTemplateIsUnaffected() {
        assertTrue(policy(true).isAllowed(null, ToolCapability.DOCUMENT_WRITE));
    }
}
