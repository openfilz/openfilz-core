package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.openfilz.dms.service.workflow.WorkflowRoles;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * {@code openfilz.security.role-token-lookup} end to end through {@link SecurityServiceImpl#authorize}:
 * <ul>
 *   <li>GROUPS: a role is granted only by the exact path {@code /<root-group>/<ROLE>} — a group under
 *       another root, a nested group, a relative or double-slash path grant nothing;</li>
 *   <li>a synthetic (server-built) principal whose claims come from {@link AutorizationMode#roleClaims}
 *       passes a CONTRIBUTOR-gated endpoint in both modes, while a token carrying only
 *       {@code realm_access} (the former synthetic shape) is refused in GROUPS mode;</li>
 *   <li>REALM_ACCESS (default) is unchanged: realm roles decide, the {@code groups} claim is ignored.</li>
 * </ul>
 */
class RoleTokenLookupAuthorizationTest {

    private static final String CREATE_FOLDER = "/api/v1/folders";          // POST: CONTRIBUTOR
    private static final String DELETE_DOCUMENT = "/api/v1/documents/1";     // DELETE: CLEANER
    private static final String ADMIN_QUOTAS = "/api/v1/admin/quotas";       // ADMIN

    private static AutorizationMode mode(RoleTokenLookup lookup, String rootGroup) {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", lookup);
        ReflectionTestUtils.setField(mode, "rootGroupName", rootGroup);
        mode.init();
        return mode;
    }

    private static SecurityServiceImpl service(AutorizationMode mode) {
        return new SecurityServiceImpl(mode, mock(OnlyOfficeProperties.class), mock(ThumbnailProperties.class), () -> false);
    }

    private static JwtAuthenticationToken token(Map<String, Object> roleClaims) {
        Jwt.Builder jwt = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject("user@openfilz.com")
                .claim("email", "user@openfilz.com");
        roleClaims.forEach(jwt::claim);
        return new JwtAuthenticationToken(jwt.build(), List.of());
    }

    private static JwtAuthenticationToken groups(String... groups) {
        return token(Map.of("groups", List.of(groups)));
    }

    private static JwtAuthenticationToken realmRoles(String... roles) {
        return token(Map.of("realm_access", Map.of("roles", List.of(roles))));
    }

    private static boolean authorize(SecurityServiceImpl service, JwtAuthenticationToken auth, HttpMethod method, String path) {
        MockServerHttpRequest request = MockServerHttpRequest.method(method, path).build();
        return service.authorize(auth, new AuthorizationContext(MockServerWebExchange.from(request)));
    }

    // ------------------------------------------------------------------------------- GROUPS mode

    @Test
    void groups_fullPathUnderRootGroupGrantsTheRole() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, "OPENFILZ"));

        assertThat(authorize(service, groups("/OPENFILZ/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, groups("/OPENFILZ/CLEANER"), HttpMethod.DELETE, DELETE_DOCUMENT)).isTrue();
        assertThat(authorize(service, groups("/OPENFILZ/ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isTrue();
    }

    @Test
    void groups_aGroupOutsideTheRootGroupGrantsNothing() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, "OPENFILZ"));

        assertThat(authorize(service, groups("/OTHER/ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isFalse();
        assertThat(authorize(service, groups("/OTHER/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
        assertThat(authorize(service, groups("/OTHER/CLEANER"), HttpMethod.DELETE, DELETE_DOCUMENT)).isFalse();
    }

    @Test
    void groups_onlyTheExactPathCounts() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, "OPENFILZ"));

        for (String almost : List.of("ADMIN", "OPENFILZ/ADMIN", "//OPENFILZ/ADMIN", "/OPENFILZ/ADMIN/SUB",
                "/X/OPENFILZ/ADMIN", "/openfilz/ADMIN", "/OPENFILZ/admin", "/OPENFILZ")) {
            assertThat(authorize(service, groups(almost), HttpMethod.GET, ADMIN_QUOTAS)).as(almost).isFalse();
        }
    }

    @Test
    void groups_aCustomRootGroupReplacesOpenfilz() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, "ACME"));

        assertThat(authorize(service, groups("/ACME/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, groups("/OPENFILZ/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
    }

    @Test
    void groups_aSlashOrSpaceInTheRootGroupSettingDoesNotDenyEveryone() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, " /OPENFILZ/ "));

        assertThat(authorize(service, groups("/OPENFILZ/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, groups("//OPENFILZ/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
        assertThat(authorize(service, groups("/OTHER/ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isFalse();
    }

    @Test
    void groups_realmRolesAloneGrantNothing() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.GROUPS, "OPENFILZ"));

        assertThat(authorize(service, realmRoles("CONTRIBUTOR", "ADMIN"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
        assertThat(authorize(service, realmRoles("ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isFalse();
    }

    /** A synthetic principal (e.g. a scoped upload token) must carry its role in the claim the mode reads. */
    @Test
    void groups_syntheticContributorPassesAContributorGatedEndpointAndNothingMore() {
        AutorizationMode mode = mode(RoleTokenLookup.GROUPS, "OPENFILZ");
        SecurityServiceImpl service = service(mode);
        JwtAuthenticationToken synthetic = token(mode.roleClaims(List.of("CONTRIBUTOR")));

        assertThat(authorize(service, synthetic, HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, synthetic, HttpMethod.DELETE, DELETE_DOCUMENT)).isFalse();
        assertThat(authorize(service, synthetic, HttpMethod.GET, ADMIN_QUOTAS)).isFalse();
    }

    @Test
    void groups_workflowRolesKeepOnlyGroupsUnderTheRoot() {
        WorkflowRoles roles = new WorkflowRoles(mode(RoleTokenLookup.GROUPS, "OPENFILZ"));

        assertThat(roles.of(groups("/OPENFILZ/CONTRIBUTOR", "/OTHER/ADMIN", "ADMIN"))).containsExactly("CONTRIBUTOR");
    }

    // ------------------------------------------------------------------ REALM_ACCESS (default)

    @Test
    void realmAccess_behaviourIsUnchanged() {
        SecurityServiceImpl service = service(mode(RoleTokenLookup.REALM_ACCESS, "OPENFILZ"));

        assertThat(authorize(service, realmRoles("CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, realmRoles("READER"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
        assertThat(authorize(service, realmRoles("CLEANER"), HttpMethod.DELETE, DELETE_DOCUMENT)).isTrue();
        assertThat(authorize(service, realmRoles("ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isTrue();
        // the groups claim is ignored in this mode, whatever its paths
        assertThat(authorize(service, groups("/OPENFILZ/ADMIN"), HttpMethod.GET, ADMIN_QUOTAS)).isFalse();
        assertThat(authorize(service, groups("/OPENFILZ/CONTRIBUTOR"), HttpMethod.POST, CREATE_FOLDER)).isFalse();
    }

    @Test
    void realmAccess_syntheticContributorKeepsTheRealmAccessOnlyShape() {
        AutorizationMode mode = mode(RoleTokenLookup.REALM_ACCESS, null);
        SecurityServiceImpl service = service(mode);
        Map<String, Object> claims = mode.roleClaims(List.of("CONTRIBUTOR"));

        assertThat(claims).containsOnlyKeys("realm_access");
        assertThat(authorize(service, token(claims), HttpMethod.POST, CREATE_FOLDER)).isTrue();
        assertThat(authorize(service, token(claims), HttpMethod.DELETE, DELETE_DOCUMENT)).isFalse();
    }
}
