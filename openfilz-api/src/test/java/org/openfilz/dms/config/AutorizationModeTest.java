package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

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
        assertTrue(groups.contains("//MYORG/CONTRIBUTOR"));
        assertTrue(groups.contains("//MYORG/CLEANER"));
        assertTrue(groups.contains("//MYORG/AUDITOR"));
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
        assertTrue(groups.contains("//OPENFILZ/CONTRIBUTOR"));
        assertTrue(groups.contains("//OPENFILZ/CLEANER"));
        assertTrue(groups.contains("//OPENFILZ/AUDITOR"));
    }
}
