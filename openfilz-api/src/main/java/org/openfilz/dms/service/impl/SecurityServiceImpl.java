package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.openfilz.dms.utils.FileConstants;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class SecurityServiceImpl extends AbstractSecurityService {

    private static final String ROLES = "roles";
    private static final String REALM_ACCESS = "realm_access";
    private static final String GROUPS = "groups";

    private final RoleTokenLookup roleTokenLookup;

    private final String rootGroupName;

    private final String graphQlBaseUrl;

    public boolean authorize(Authentication auth, AuthorizationContext context) {
        ServerHttpRequest request = context.getExchange().getRequest();
        HttpMethod method = request.getMethod();
        if(isDeleteAccess(method)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CLEANER);
        }
        String path = request.getPath().value();
        int i = path.indexOf(RestApiVersion.API_PREFIX);
        if(i < 0) {
            return isGraphQlSearch(graphQlBaseUrl, path) && isAuthorized((JwtAuthenticationToken) auth, Role.READER, Role.CONTRIBUTOR);
        }
        path = path.substring(i + RestApiVersion.API_PREFIX.length());
        if (isQueryOrSearch(method, path))
            return isAuthorized((JwtAuthenticationToken) auth, Role.READER, Role.CONTRIBUTOR);
        if(isAudit(path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.AUDITOR);
        }
        if(isInsertOrUpdateAccess(method, path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR);
        }
        return false;
    }

    private boolean isAuthorized(JwtAuthenticationToken auth, Role... requiredRoles) {
        /*if(roleTokenLookup == RoleTokenLookup.AUTHORITIES) {
            return isInAuthorities(auth, requiredRoles);
        }*/
        if(roleTokenLookup == RoleTokenLookup.GROUPS) {
            return isInGroups(auth, requiredRoles);
        }
        return isInRealmRoles(auth, requiredRoles);
    }

    private boolean isInGroups(JwtAuthenticationToken auth, Role[] requiredRoles) {
        List<String> groups = (List<String>) auth.getTokenAttributes().get(GROUPS);
        if(groups != null && !groups.isEmpty()) {
            return groups.stream().filter(g->g.startsWith(FileConstants.SLASH + rootGroupName + FileConstants.SLASH))
                    .map(g->g.substring(rootGroupName.length() + 2))
                    .anyMatch(g -> Arrays.stream(requiredRoles).anyMatch(r->r.toString().equals(g)));
        }
        return false;
    }

    private boolean isInRealmRoles(JwtAuthenticationToken auth, Role... requiredRoles) {
        List<String> accessRoles = getAccessRoles(auth);
        return accessRoles != null && !accessRoles.isEmpty() && Arrays.stream(requiredRoles).anyMatch(requiredRole -> accessRoles.contains(requiredRole.toString()));
    }

    private List<String> getAccessRoles(JwtAuthenticationToken auth) {
        return getRealmAccessRoles(auth);
    }

    private List<String> getRealmAccessRoles(JwtAuthenticationToken auth) {
        Map<String, Object> tokenAttributes = auth.getTokenAttributes();
        if(tokenAttributes != null &&  tokenAttributes.containsKey(REALM_ACCESS)) {
            Map<String, List<String>> realmAccess = (Map<String, List<String>>) tokenAttributes.get(REALM_ACCESS);
            if(realmAccess != null && realmAccess.containsKey(ROLES)) {
                return realmAccess.get(ROLES);
            }
        }
        return null;
    }

    /*private boolean isInAuthorities(Authentication auth, Role... requiredRoles) {
        return auth.getAuthorities() != null && auth.getAuthorities().stream().anyMatch(role -> Arrays.stream(requiredRoles).anyMatch(r->r.toString().equals(role.getAuthority())));
    }*/


}
