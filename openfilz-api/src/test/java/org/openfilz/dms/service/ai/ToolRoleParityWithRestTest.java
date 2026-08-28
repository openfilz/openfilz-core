package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.security.SecurityService;
import org.openfilz.dms.security.impl.SecurityServiceImpl;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>The MCP/tool role model must be exactly the REST role model.</b>
 * <p>
 * {@link DefaultAiToolRolePolicy} necessarily restates the rules {@link SecurityServiceImpl}
 * applies to HTTP requests, because a tool call has no request to match on — it runs in-process on
 * a tool thread. A restatement can drift: change the role for deletes in the security chain and
 * the tool layer would silently keep the old one, re-opening the very gap this was written to
 * close.
 * <p>
 * So rather than trusting the two tables to stay aligned, this test drives <em>both</em> and
 * asserts they agree. For each capability it issues the equivalent REST request through
 * {@code SecurityService.authorize(...)} and compares the verdict with
 * {@code AiToolRolePolicy.isAllowed(...)}, across every single-role token plus a few combinations.
 * A divergence fails here, in milliseconds, instead of becoming a privilege escalation.
 *
 * @see DefaultAiToolRolePolicyTest for what the mapping is
 * @see org.openfilz.dms.e2e.McpRoleEnforcementIT for the end-to-end proof over real HTTP
 */
class ToolRoleParityWithRestTest {

    /**
     * A REST request that lands on the same rule the capability represents. Paths are chosen to
     * hit the intended branch of {@code AbstractSecurityService.authorize}.
     */
    private static final Map<ToolCapability, MockServerHttpRequest.BaseBuilder<?>> REST_EQUIVALENT = Map.of(
            ToolCapability.DOCUMENT_READ, MockServerHttpRequest.get("/api/v1/documents/search"),
            ToolCapability.DOCUMENT_WRITE, MockServerHttpRequest.post("/api/v1/folders"),
            ToolCapability.DOCUMENT_DELETE, MockServerHttpRequest.delete("/api/v1/documents/x"),
            ToolCapability.AUDIT_READ, MockServerHttpRequest.get("/api/v1/audit"),
            ToolCapability.SIGNATURE_WRITE, MockServerHttpRequest.post("/api/v1/signatures"));

    /** Every role individually, plus the combinations the all-of rules need. */
    private static final List<Set<String>> ROLE_SETS = List.of(
            Set.of(),
            Set.of(Role.READER.toString()),
            Set.of(Role.CONTRIBUTOR.toString()),
            Set.of(Role.CLEANER.toString()),
            Set.of(Role.AUDITOR.toString()),
            Set.of(Role.SIGN_REQUESTER.toString()),
            Set.of(Role.CONTRIBUTOR.toString(), Role.SIGN_REQUESTER.toString()),
            Set.of(Role.READER.toString(), Role.CONTRIBUTOR.toString(), Role.CLEANER.toString(),
                    Role.AUDITOR.toString(), Role.SIGN_REQUESTER.toString()));

    private static JwtAuthenticationToken tokenWith(Set<String> roles) {
        return new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("email", "someone@test.com")
                .claim("realm_access", Map.of("roles", List.copyOf(roles)))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    private static SecurityServiceImpl restSecurityService(boolean requireRequesterRole) {
        // Realm-role lookup (the default). AutorizationMode reads its mode from @Value + @PostConstruct
        // in production, so drive it the same way here rather than faking the flag.
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.REALM_ACCESS);
        mode.init();

        SecurityServiceImpl service = new SecurityServiceImpl(
                mode, new OnlyOfficeProperties(), new ThumbnailProperties());
        // @Value-injected in production.
        ReflectionTestUtils.setField(service, "requireSignatureRequesterRole", requireRequesterRole);
        ReflectionTestUtils.setField(service, "graphQlBaseUrl", "/graphql/v1");
        return service;
    }

    private static AuthorizationContext contextFor(ToolCapability capability) {
        return new AuthorizationContext(
                MockServerWebExchange.from((MockServerHttpRequest) REST_EQUIVALENT.get(capability).build()));
    }

    private void assertParity(boolean requireRequesterRole) {
        SecurityService rest = restSecurityService(requireRequesterRole);
        SignatureProperties signatureProperties = new SignatureProperties();
        signatureProperties.setRequireRequesterRole(requireRequesterRole);
        AiToolRolePolicy tools = new DefaultAiToolRolePolicy(
                java.util.Optional.of(rest), signatureProperties);

        for (ToolCapability capability : REST_EQUIVALENT.keySet()) {
            for (Set<String> roles : ROLE_SETS) {
                JwtAuthenticationToken token = tokenWith(roles);

                boolean restVerdict = rest.authorize(token, contextFor(capability));
                boolean toolVerdict = tools.isAllowed(token, capability);

                assertThat(toolVerdict)
                        .as("""
                                Tool layer and REST disagree for %s with roles %s: \
                                REST says %s, the tool layer says %s. The two role models must be \
                                identical — update DefaultAiToolRolePolicy.rolesFor to match \
                                AbstractSecurityService, or the tool front-ends become a way \
                                around the API's authorization.""",
                                capability, roles.isEmpty() ? "(none)" : roles, restVerdict, toolVerdict)
                        .isEqualTo(restVerdict);
            }
        }
    }

    @Test
    @DisplayName("tool capabilities and REST endpoints authorise identically")
    void toolLayerMatchesRest() {
        assertParity(false);
    }

    @Test
    @DisplayName("...including when the e-Sign requester role is required")
    void toolLayerMatchesRestWithRequesterRole() {
        assertParity(true);
    }
}
