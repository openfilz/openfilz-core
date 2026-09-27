package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.List.of;

/**
 * Where role information lives in the caller's JWT ({@code openfilz.security.role-token-lookup}):
 * <ul>
 *   <li>{@code REALM_ACCESS} (default) — Keycloak realm roles, claim {@code realm_access.roles};</li>
 *   <li>{@code GROUPS} — Keycloak group membership, claim {@code groups} with full paths, a role
 *       being granted only by the exact path {@code /<root-group>/<ROLE>}
 *       ({@code openfilz.security.root-group}, default {@code OPENFILZ}).</li>
 * </ul>
 * Read at runtime (plain {@code @Value}), so the same native image serves both modes.
 */
@Configuration
public class AutorizationMode {

    public static final String REALM_ACCESS_CLAIM = "realm_access";
    public static final String ROLES_CLAIM = "roles";
    public static final String GROUPS_CLAIM = "groups";
    private static final String DEFAULT_ROOT_GROUP = "OPENFILZ";

    private static final List<String> LICENSED_USER_DEFAULT_ROLES = of(Role.CONTRIBUTOR.toString(), Role.CLEANER.toString(), Role.AUDITOR.toString());

    @Value("${openfilz.security.role-token-lookup:REALM_ACCESS}")
    private RoleTokenLookup roleTokenLookup;

    @Getter
    @Value("${openfilz.security.root-group:#{null}}")
    private String rootGroupName;

    private boolean rolesBasedOnGroups;
    private List<String> licensedUserDefaultGroups = null;


    @PostConstruct
    public void init() {
        rolesBasedOnGroups = roleTokenLookup == RoleTokenLookup.GROUPS;
        if(!rolesBasedOnGroups) {
            rootGroupName = null;
        } else {
            rootGroupName = normalizeRootGroup(rootGroupName);
            // Full Keycloak group paths, exactly as the "groups" claim carries them (full.path=true)
            licensedUserDefaultGroups = LICENSED_USER_DEFAULT_ROLES.stream().map(this::groupPath).toList();
        }
    }

    /**
     * {@code " /OPENFILZ/ "} and {@code "OPENFILZ"} name the same root group: trim, strip leading and
     * trailing '/', blank → {@code OPENFILZ}. Without it a stray slash or space in
     * {@code OPENFILZ_SECURITY_ROOT_GROUP} would build {@code //OPENFILZ/ROLE} and silently deny everyone.
     */
    static String normalizeRootGroup(String rootGroup) {
        if (rootGroup == null) {
            return DEFAULT_ROOT_GROUP;
        }
        String name = rootGroup.trim();
        int start = 0;
        int end = name.length();
        while (start < end && name.charAt(start) == '/') start++;
        while (end > start && name.charAt(end - 1) == '/') end--;
        name = name.substring(start, end).trim();
        return name.isEmpty() ? DEFAULT_ROOT_GROUP : name;
    }

    public boolean areRolesBasedOnGroups() {
        return rolesBasedOnGroups;
    }

    public List<String> getLicensedUserDefaultGroups() {
        return rolesBasedOnGroups ? licensedUserDefaultGroups : null;
    }

    public List<String> getLicensedUserDefaultRoles() {
        return rolesBasedOnGroups ? null : LICENSED_USER_DEFAULT_ROLES;
    }

    /** The group path granting {@code role} in GROUPS mode: {@code /<root-group>/<role>}; {@code null} in REALM_ACCESS mode. */
    public String groupPath(String role) {
        return rolesBasedOnGroups ? "/" + rootGroupName + "/" + role : null;
    }

    /**
     * The role-bearing claims a synthetic (server-built) JWT must carry so that downstream role
     * checks grant exactly {@code roles}, whatever the configured mode: {@code realm_access.roles}
     * always, plus {@code groups} ({@code /<root-group>/<ROLE>}) in GROUPS mode. Keeps both claims
     * in step so a synthetic principal is never role-less in one mode and privileged in the other.
     */
    public Map<String, Object> roleClaims(Collection<String> roles) {
        List<String> names = List.copyOf(roles);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put(REALM_ACCESS_CLAIM, Map.of(ROLES_CLAIM, names));
        if (rolesBasedOnGroups) {
            claims.put(GROUPS_CLAIM, names.stream().map(this::groupPath).toList());
        }
        return claims;
    }
}
