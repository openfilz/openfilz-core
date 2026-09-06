package org.openfilz.dms.service.workflow;

import org.openfilz.dms.config.AutorizationMode;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Reads the caller's role names from the JWT the way {@code AbstractSecurityService} does
 * (realm roles, or groups under the root group) — the engine needs the plain names to match
 * a ROLE task's {@code candidate_role}.
 */
@Component
public class WorkflowRoles {

    private final AutorizationMode mode;

    public WorkflowRoles(AutorizationMode mode) {
        this.mode = mode;
    }

    @SuppressWarnings("unchecked")
    public List<String> of(Authentication auth) {
        if (!(auth instanceof JwtAuthenticationToken jwt)) return List.of();
        if (mode.areRolesBasedOnGroups()) {
            List<String> groups = jwt.getToken().getClaim("groups");
            if (groups == null) return List.of();
            String prefix = "/" + mode.getRootGroupName() + "/";
            return groups.stream().filter(g -> g.startsWith(prefix)).map(g -> g.substring(prefix.length())).toList();
        }
        Map<String, Object> realmAccess = jwt.getToken().getClaim("realm_access");
        if (CollectionUtils.isEmpty(realmAccess)) return List.of();
        Object roles = realmAccess.getOrDefault("roles", Collections.emptyList());
        return roles instanceof List<?> l ? l.stream().map(String::valueOf).toList() : List.of();
    }
}
