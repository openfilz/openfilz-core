package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.security.SecurityService;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the capability → role mapping: a tool call must be authorised exactly as the equivalent
 * REST call is.
 * <p>
 * This is the regression guard for a confirmed privilege escalation. The role model used to be
 * enforced only by the HTTP security chain, which matches on request method and path — and tools
 * never produce a request. A READER-only token was refused {@code POST /api/v1/folders} with 403
 * and simultaneously allowed to create the folder through {@code tools/call createFolder}.
 *
 * @see McpRoleEnforcementIT the end-to-end half, over the real HTTP + JWT stack
 */
class DefaultAiToolRolePolicyTest {

    /** Stands in for the real role extraction: an ANY-of check over the token's realm roles. */
    private static SecurityService securityServiceGranting(String... grantedRoles) {
        Set<String> granted = Set.of(grantedRoles);
        return new SecurityService() {
            @Override
            public boolean authorize(Authentication auth,
                                     org.springframework.security.web.server.authorization.AuthorizationContext context) {
                throw new UnsupportedOperationException("not used by the role policy");
            }

            @Override
            public boolean isAuthorized(JwtAuthenticationToken auth, String role) {
                return granted.contains(role);
            }

            @Override
            public boolean isAuthorized(JwtAuthenticationToken auth, List<String> anyRoles) {
                return anyRoles.stream().anyMatch(granted::contains);
            }
        };
    }

    private static AiToolRolePolicy policyFor(boolean requireRequesterRole, String... grantedRoles) {
        SignatureProperties signatureProperties = new SignatureProperties();
        signatureProperties.setRequireRequesterRole(requireRequesterRole);
        return new DefaultAiToolRolePolicy(
                Optional.of(securityServiceGranting(grantedRoles)), signatureProperties);
    }

    private static JwtAuthenticationToken jwt() {
        return new JwtAuthenticationToken(Jwt.withTokenValue("t")
                .header("alg", "none")
                .claim("email", "someone@test.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build());
    }

    @Test
    @DisplayName("READER may read and search, and may NOT write")
    void readerCanReadButNotWrite() {
        AiToolRolePolicy policy = policyFor(false, Role.READER.toString());

        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_READ))
                .as("READER must be able to search and read documents, as over REST").isTrue();
        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_WRITE))
                .as("""
                        READER must NOT be able to write. This is the exact escalation this class \
                        exists to prevent: the same token is refused POST /api/v1/folders.""")
                .isFalse();
        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_DELETE)).isFalse();
        assertThat(policy.isAllowed(jwt(), ToolCapability.AUDIT_READ)).isFalse();
    }

    @Test
    @DisplayName("CONTRIBUTOR may read and write, but may not delete or read the audit trail")
    void contributorCanReadAndWrite() {
        AiToolRolePolicy policy = policyFor(false, Role.CONTRIBUTOR.toString());

        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_READ)).isTrue();
        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_WRITE)).isTrue();
        assertThat(policy.isAllowed(jwt(), ToolCapability.DOCUMENT_DELETE))
                .as("delete is CLEANER's, not CONTRIBUTOR's — as over REST").isFalse();
        assertThat(policy.isAllowed(jwt(), ToolCapability.AUDIT_READ)).isFalse();
    }

    @Test
    @DisplayName("CLEANER may delete; AUDITOR may read the audit trail")
    void specialistRolesMapToTheirOwnCapability() {
        assertThat(policyFor(false, Role.CLEANER.toString())
                .isAllowed(jwt(), ToolCapability.DOCUMENT_DELETE)).isTrue();
        assertThat(policyFor(false, Role.AUDITOR.toString())
                .isAllowed(jwt(), ToolCapability.AUDIT_READ)).isTrue();
        // ...and neither of them inherits document write.
        assertThat(policyFor(false, Role.CLEANER.toString())
                .isAllowed(jwt(), ToolCapability.DOCUMENT_WRITE)).isFalse();
        assertThat(policyFor(false, Role.AUDITOR.toString())
                .isAllowed(jwt(), ToolCapability.DOCUMENT_WRITE)).isFalse();
    }

    @Test
    @DisplayName("e-Sign writes need CONTRIBUTOR, and SIGN_REQUESTER *as well* when required")
    void signatureWriteFollowsTheRequesterRoleToggle() {
        // Toggle off: the ordinary write role initiates envelopes.
        assertThat(policyFor(false, Role.CONTRIBUTOR.toString())
                .isAllowed(jwt(), ToolCapability.SIGNATURE_WRITE)).isTrue();
        assertThat(policyFor(false, Role.SIGN_REQUESTER.toString())
                .isAllowed(jwt(), ToolCapability.SIGNATURE_WRITE)).isFalse();

        // Toggle on: AbstractSecurityService uses hasAllRoles(CONTRIBUTOR, SIGN_REQUESTER), so the
        // requester role is an ADDITIONAL requirement, never a substitute. Modelling it as "any of"
        // would let a bare SIGN_REQUESTER initiate envelopes.
        assertThat(policyFor(true, Role.SIGN_REQUESTER.toString())
                .isAllowed(jwt(), ToolCapability.SIGNATURE_WRITE))
                .as("SIGN_REQUESTER alone must not be enough").isFalse();
        assertThat(policyFor(true, Role.CONTRIBUTOR.toString())
                .isAllowed(jwt(), ToolCapability.SIGNATURE_WRITE))
                .as("CONTRIBUTOR alone must not be enough once the requester role is required").isFalse();
        assertThat(policyFor(true, Role.CONTRIBUTOR.toString(), Role.SIGN_REQUESTER.toString())
                .isAllowed(jwt(), ToolCapability.SIGNATURE_WRITE))
                .as("both together is what the REST rule requires").isTrue();
    }

    @Test
    @DisplayName("IDENTITY_READ (whoami) is granted to any authenticated caller, roles or not")
    void identityReadNeedsNoRole() {
        assertThat(policyFor(false)
                .isAllowed(jwt(), ToolCapability.IDENTITY_READ))
                .as("a caller with no OpenFilz role must still be able to ask who it is").isTrue();
        assertThat(policyFor(false, Role.READER.toString())
                .isAllowed(jwt(), ToolCapability.IDENTITY_READ)).isTrue();
    }

    @Test
    @DisplayName("share capabilities are refused in Community — there is no sharing model")
    void shareCapabilitiesAreEnterpriseOnly() {
        AiToolRolePolicy policy = policyFor(false,
                Role.READER.toString(), Role.CONTRIBUTOR.toString(),
                Role.CLEANER.toString(), Role.AUDITOR.toString());

        assertThat(policy.isAllowed(jwt(), ToolCapability.SHARE_READ))
                .as("VIEW_SHARE is an enterprise role; core must not grant it to anyone").isFalse();
        assertThat(policy.isAllowed(jwt(), ToolCapability.SHARE_WRITE))
                .as("EDIT_SHARE is an enterprise role; core must not grant it to anyone").isFalse();
    }

    @Test
    @DisplayName("with no SecurityService bean there is no role model to enforce")
    void noSecurityServiceMeansAuthorizationIsOff() {
        // openfilz.security.no-auth=true leaves no SecurityService bean and no JWT. Refusing there
        // would break the tools on every no-auth deployment; permitting matches the security chain,
        // which permits every request in that mode.
        AiToolRolePolicy noAuth = new DefaultAiToolRolePolicy(Optional.empty(), new SignatureProperties());

        assertThat(noAuth.isAllowed(new TestingAuthenticationToken("anonymous", "n/a"),
                ToolCapability.DOCUMENT_WRITE)).isTrue();
    }

    @Test
    @DisplayName("a non-JWT principal is refused rather than guessed at")
    void syntheticPrincipalsAreRefused() {
        // Upload tokens and e-Sign signer tokens are synthetic principals with no realm roles.
        // They never call a tool today; failing closed keeps it that way.
        Authentication synthetic = new TestingAuthenticationToken("upload-bot", "n/a");

        assertThat(policyFor(false, Role.CONTRIBUTOR.toString())
                .isAllowed(synthetic, ToolCapability.DOCUMENT_READ)).isFalse();
    }

    @Test
    @DisplayName("no Authentication means no authenticated front-end, not a user without roles")
    void unboundToolsAreNotRoleChecked() {
        // The definitions-only template instance and unit tests build tools with no caller. Both
        // real front-ends always bind one — MCP refuses the call outright without it, and the chat
        // pipeline takes it from the security context — so there is no role decision to make here.
        assertThat(policyFor(false).isAllowed(null, ToolCapability.DOCUMENT_WRITE)).isTrue();
    }

    @Test
    @DisplayName("every ToolCapability is mapped — a new one cannot ship unclassified")
    void everyCapabilityIsMapped() {
        DefaultAiToolRolePolicy policy = (DefaultAiToolRolePolicy) policyFor(false);
        Map<ToolCapability, Boolean> enterpriseOnly = Map.of(
                ToolCapability.SHARE_READ, true,
                ToolCapability.SHARE_WRITE, true,
                ToolCapability.COMMENT_READ, true,
                ToolCapability.COMMENT_WRITE, true);

        for (ToolCapability capability : ToolCapability.values()) {
            DefaultAiToolRolePolicy.RoleRequirement required = policy.rolesFor(capability);
            if (enterpriseOnly.containsKey(capability)) {
                assertThat(required.isNever()).as("%s is enterprise-only in core", capability).isTrue();
            } else {
                assertThat(required.isNever())
                        .as("%s has no role mapping — every core capability must name its roles",
                                capability)
                        .isFalse();
            }
        }
    }
}
