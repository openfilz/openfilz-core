package org.openfilz.dms.service.workflow.impl;

import org.openfilz.dms.service.workflow.WorkflowActorResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Core resolver: a synthetic {@link JwtAuthenticationToken} whose {@code email} claim is the
 * trusted identity stored on the row, so audit rows written by the sweeper or by queued
 * actions are attributed to a real person without any Keycloak round-trip (same idea as the
 * e-Sign actor resolver).
 */
@Service
public class DefaultWorkflowActorResolver implements WorkflowActorResolver {

    @Override
    public Mono<Authentication> authenticationFor(String email) {
        Instant now = Instant.now();
        Jwt jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .claim("sub", email)
                .claim("email", email)
                .claim("preferred_username", email)
                .claim("name", email)
                .claim("azp", AZP_WORKFLOW_SERVICE)
                .claim("realm_access", Map.of("roles", List.of()))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .build();
        return Mono.just(new JwtAuthenticationToken(jwt, List.of(), email));
    }
}
