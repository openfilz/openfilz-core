package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import java.util.List;

import static java.util.List.of;

@Configuration
public class AutorizationMode {

    private static final List<String> LICENSED_USER_DEFAULT_ROLES = of(Role.CONTRIBUTOR.toString(), Role.CLEANER.toString(), Role.AUDITOR.toString());

    @Value("${openfilz.security.role-token-lookup}")
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
            if(rootGroupName == null) {
                rootGroupName = "OPENFILZ";
            }
            licensedUserDefaultGroups = LICENSED_USER_DEFAULT_ROLES.stream().map(r -> "//" + rootGroupName + "/" + r).toList();
        }
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
}
