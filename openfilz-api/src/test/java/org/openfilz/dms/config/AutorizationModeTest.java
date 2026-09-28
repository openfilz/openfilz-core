package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AutorizationModeTest {

    @Test
    void init_withRealmAccess_setsRolesNotBasedOnGroups() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.REALM_ACCESS);
        ReflectionTestUtils.setField(mode, "rootGroupName", "SOMETHING");

        mode.init();

        assertFalse(mode.areRolesBasedOnGroups());
        assertNull(mode.getRootGroupName());
    }

    @Test
    void init_withRealmAccess_licensedUserDefaultRoles_notNull() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.REALM_ACCESS);

        mode.init();

        List<String> roles = mode.getLicensedUserDefaultRoles();
        assertNotNull(roles);
        assertTrue(roles.contains("CONTRIBUTOR"));
        assertTrue(roles.contains("CLEANER"));
        assertTrue(roles.contains("AUDITOR"));
    }

    @Test
    void init_withRealmAccess_licensedUserDefaultGroups_isNull() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.REALM_ACCESS);

        mode.init();

        assertNull(mode.getLicensedUserDefaultGroups());
    }

    @Test
    void init_withGroups_andNullRootGroup_usesDefaultOpenfilz() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", null);

        mode.init();

        assertTrue(mode.areRolesBasedOnGroups());
        assertEquals("OPENFILZ", mode.getRootGroupName());
    }

    @Test
    void init_withGroups_andCustomRootGroup_usesCustomGroup() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", "MYORG");

        mode.init();

        assertTrue(mode.areRolesBasedOnGroups());
        assertEquals("MYORG", mode.getRootGroupName());
    }

    @Test
    void init_withGroups_licensedUserDefaultGroups_containsFormattedPaths() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", "MYORG");

        mode.init();

        List<String> groups = mode.getLicensedUserDefaultGroups();
        assertNotNull(groups);
        assertEquals(3, groups.size());
        assertTrue(groups.contains("/MYORG/CONTRIBUTOR"));
        assertTrue(groups.contains("/MYORG/CLEANER"));
        assertTrue(groups.contains("/MYORG/AUDITOR"));
    }

    @Test
    void init_withGroups_licensedUserDefaultRoles_isNull() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", "OPENFILZ");

        mode.init();

        assertNull(mode.getLicensedUserDefaultRoles());
    }

    @Test
    void init_withGroups_andDefaultRootGroup_formatsCorrectly() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", null);

        mode.init();

        List<String> groups = mode.getLicensedUserDefaultGroups();
        assertNotNull(groups);
        assertTrue(groups.contains("/OPENFILZ/CONTRIBUTOR"));
        assertTrue(groups.contains("/OPENFILZ/CLEANER"));
        assertTrue(groups.contains("/OPENFILZ/AUDITOR"));
    }

    /** License-server looks groups up by path: exactly "/ROOT/ROLE", never "//ROOT/ROLE". */
    @Test
    void init_withGroups_licensedUserDefaultGroups_areSingleSlashKeycloakPaths() {
        AutorizationMode mode = groups("OPENFILZ");

        assertEquals(List.of("/OPENFILZ/CONTRIBUTOR", "/OPENFILZ/CLEANER", "/OPENFILZ/AUDITOR"),
                mode.getLicensedUserDefaultGroups());
        mode.getLicensedUserDefaultGroups().forEach(g -> assertFalse(g.startsWith("//"), g));
    }

    @Test
    void init_withGroups_andBlankRootGroup_usesDefaultOpenfilz() {
        AutorizationMode mode = groups("  ");

        assertEquals("OPENFILZ", mode.getRootGroupName());
        assertEquals("/OPENFILZ/READER", mode.groupPath("READER"));
    }

    @Test
    void roleClaims_inRealmAccessMode_carryRealmRolesOnly() {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.REALM_ACCESS);
        mode.init();

        Map<String, Object> claims = mode.roleClaims(List.of("CONTRIBUTOR"));

        assertEquals(Map.of("realm_access", Map.of("roles", List.of("CONTRIBUTOR"))), claims);
        assertNull(mode.groupPath("CONTRIBUTOR"));
    }

    @Test
    void roleClaims_inGroupsMode_carryRealmRolesAndFullGroupPaths() {
        AutorizationMode mode = groups("MYORG");

        Map<String, Object> claims = mode.roleClaims(List.of("CONTRIBUTOR", "READER"));

        assertEquals(Map.of("roles", List.of("CONTRIBUTOR", "READER")), claims.get("realm_access"));
        assertEquals(List.of("/MYORG/CONTRIBUTOR", "/MYORG/READER"), claims.get("groups"));
    }

    @Test
    void roleClaims_withNoRole_grantNothingInEitherMode() {
        AutorizationMode mode = groups("OPENFILZ");

        Map<String, Object> claims = mode.roleClaims(List.of());

        assertEquals(Map.of("roles", List.of()), claims.get("realm_access"));
        assertEquals(List.of(), claims.get("groups"));
    }

    @Test
    void init_withGroups_rootGroupIsNormalised() {
        for (String raw : List.of("/OPENFILZ", "OPENFILZ/", "/OPENFILZ/", " OPENFILZ ", " /OPENFILZ/ ", "//OPENFILZ//")) {
            AutorizationMode mode = groups(raw);
            assertEquals("OPENFILZ", mode.getRootGroupName(), raw);
            assertEquals("/OPENFILZ/CONTRIBUTOR", mode.groupPath("CONTRIBUTOR"), raw);
        }
    }

    @Test
    void init_withGroups_slashesOnlyRootGroupFallsBackToOpenfilz() {
        assertEquals("OPENFILZ", groups("/").getRootGroupName());
        assertEquals("OPENFILZ", groups(" // ").getRootGroupName());
    }

    @Test
    void init_withGroups_innerSlashesAndCaseAreKept() {
        assertEquals("/ACME/DMS/READER", groups("/ACME/DMS/").groupPath("READER"));
        assertEquals("/acme/READER", groups("acme").groupPath("READER"));
    }

    private static AutorizationMode groups(String root) {
        AutorizationMode mode = new AutorizationMode();
        ReflectionTestUtils.setField(mode, "roleTokenLookup", RoleTokenLookup.GROUPS);
        ReflectionTestUtils.setField(mode, "rootGroupName", root);
        mode.init();
        return mode;
    }
}
