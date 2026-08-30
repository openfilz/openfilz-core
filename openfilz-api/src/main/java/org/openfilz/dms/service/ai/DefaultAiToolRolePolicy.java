package org.openfilz.dms.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.security.SecurityService;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Core {@link AiToolRolePolicy}: maps each {@link ToolCapability} to the same roles the REST API
 * requires for the equivalent operation, so a tool call and an HTTP call are authorised alike.
 *
 * <table>
 *   <caption>Capability → role</caption>
 *   <tr><th>Capability</th><th>Roles</th><th>REST equivalent</th></tr>
 *   <tr><td>{@code DOCUMENT_READ}</td><td>READER or CONTRIBUTOR</td><td>read/search endpoints</td></tr>
 *   <tr><td>{@code DOCUMENT_WRITE}</td><td>CONTRIBUTOR</td><td>insert/update endpoints</td></tr>
 *   <tr><td>{@code DOCUMENT_DELETE}</td><td>CLEANER</td><td>delete endpoints</td></tr>
 *   <tr><td>{@code AUDIT_READ}</td><td>AUDITOR</td><td>{@code /api/v1/audit}</td></tr>
 *   <tr><td>{@code SIGNATURE_WRITE}</td><td>CONTRIBUTOR, plus SIGN_REQUESTER when required</td><td>e-Sign writes</td></tr>
 *   <tr><td>{@code SHARE_READ} / {@code SHARE_WRITE}</td><td><em>refused</em></td><td>enterprise only</td></tr>
 * </table>
 *
 * Role extraction is delegated to {@link SecurityService}, which already resolves roles from
 * either realm roles or Keycloak groups depending on {@code openfilz.security.role-token-lookup}.
 * Reimplementing that here would silently diverge on group-based deployments.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DefaultAiToolRolePolicy implements AiToolRolePolicy {

    /**
     * Optional on purpose. {@code SecurityServiceImpl} is conditional, so no bean exists when the
     * deployment has no role model to enforce — notably {@code openfilz.security.no-auth=true},
     * where the security chain permits everything and there is no JWT to read roles from. Absent
     * means "authorization is switched off here", not "this user has no roles".
     */
    private final Optional<SecurityService> securityService;
    private final SignatureProperties signatureProperties;

    @Override
    public boolean isAllowed(Authentication authentication, ToolCapability capability) {
        // No authenticated caller means the tools were not built through an authenticated
        // front-end. Both real ones always bind an Authentication — the MCP server refuses the
        // call outright without one, and the chat pipeline takes it from the security context —
        // so this is the definitions-only template instance or a unit test, and there is no role
        // decision to make. It is NOT "a user with no roles".
        if (authentication == null) {
            return true;
        }
        if (securityService.isEmpty()) {
            return true;
        }
        if (!(authentication instanceof JwtAuthenticationToken jwt)) {
            // Synthetic principals (upload tokens, e-Sign signer tokens) carry no realm roles.
            // They are never the caller of a tool today; refuse rather than guess.
            log.warn("Tool capability {} requested by a non-JWT principal ({}) — refused",
                    capability, authentication.getClass().getSimpleName());
            return false;
        }
        RoleRequirement required = rolesFor(capability);
        if (required.isNever()) {
            // Enterprise-only capability on a Community deployment.
            log.debug("Tool capability {} is not available in this edition — refused", capability);
            return false;
        }
        if (required.anyAuthenticated()) {
            // Reaching this line means the caller carries a validated JWT — that is the whole
            // requirement (e.g. IDENTITY_READ: your own identity needs no role).
            return true;
        }
        SecurityService service = securityService.get();
        return required.all()
                ? required.roles().stream().allMatch(role -> service.isAuthorized(jwt, role))
                : service.isAuthorized(jwt, required.roles());
    }

    /**
     * Which roles grant a capability, and whether the caller needs <em>any</em> of them or
     * <em>all</em> of them. The distinction is not cosmetic: {@code AbstractSecurityService} gates
     * e-Sign initiation on CONTRIBUTOR <b>and</b> SIGN_REQUESTER, and the enterprise layer gates
     * share writes on CONTRIBUTOR <b>and</b> EDIT_SHARE. Modelling those as "any of" would grant a
     * bare SIGN_REQUESTER the right to initiate envelopes.
     */
    protected RoleRequirement rolesFor(ToolCapability capability) {
        return switch (capability) {
            // Your own identity needs no role — any authenticated caller may ask who it is.
            case IDENTITY_READ -> RoleRequirement.authenticated();
            case DOCUMENT_READ -> RoleRequirement.any(Role.READER.toString(), Role.CONTRIBUTOR.toString());
            case DOCUMENT_WRITE -> RoleRequirement.any(Role.CONTRIBUTOR.toString());
            case DOCUMENT_DELETE -> RoleRequirement.any(Role.CLEANER.toString());
            case AUDIT_READ -> RoleRequirement.any(Role.AUDITOR.toString());
            // e-Sign GETs are READER/CONTRIBUTOR, matching AbstractSecurityService.isSignatureAuthorized.
            case SIGNATURE_READ -> RoleRequirement.any(Role.READER.toString(), Role.CONTRIBUTOR.toString());
            // Mirrors AbstractSecurityService.isSignatureAuthorized: the requester role is an
            // ADDITIONAL requirement on top of CONTRIBUTOR, never a substitute for it.
            case SIGNATURE_WRITE -> signatureProperties.isRequireRequesterRole()
                    ? RoleRequirement.all(Role.CONTRIBUTOR.toString(), Role.SIGN_REQUESTER.toString())
                    : RoleRequirement.any(Role.CONTRIBUTOR.toString());
            // Enterprise-only: no sharing or comment model in Community, so refuse outright.
            case SHARE_READ, SHARE_WRITE, COMMENT_READ, COMMENT_WRITE -> RoleRequirement.never();
        };
    }

    /**
     * A set of roles plus how to combine them. {@code never()} means the capability does not exist
     * in this edition, which is not the same as "nobody happens to hold the role" — and
     * {@code authenticated()} means a validated caller suffices, which is not the same as "no
     * requirement at all" (an unauthenticated call is still refused before this is consulted).
     */
    public record RoleRequirement(List<String> roles, boolean all, boolean anyAuthenticated) {

        public static RoleRequirement any(String... roles) {
            return new RoleRequirement(List.of(roles), false, false);
        }

        public static RoleRequirement all(String... roles) {
            return new RoleRequirement(List.of(roles), true, false);
        }

        public static RoleRequirement never() {
            return new RoleRequirement(List.of(), false, false);
        }

        /** Any authenticated caller, regardless of roles (e.g. {@code IDENTITY_READ}). */
        public static RoleRequirement authenticated() {
            return new RoleRequirement(List.of(), false, true);
        }

        public boolean isNever() {
            return roles.isEmpty() && !anyAuthenticated;
        }
    }
}
