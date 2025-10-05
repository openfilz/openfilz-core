package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.openfilz.dms.service.SecurityService;
import org.openfilz.dms.utils.FileConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public abstract class AbstractSecurityService implements SecurityService {

    protected static final String ROLES = "roles";
    protected static final String REALM_ACCESS = "realm_access";
    protected static final String GROUPS = "groups";

    @Value("${openfilz.security.role-token-lookup:#{null}}")
    protected RoleTokenLookup roleTokenLookup;

    @Value("${openfilz.security.root-group:#{null}}")
    protected String rootGroupName;

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    public boolean authorize(Authentication auth, AuthorizationContext context) {
        ServerHttpRequest request = context.getExchange().getRequest();
        HttpMethod method = request.getMethod();
        if(isDeleteAccess(request)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CLEANER);
        }
        String path = request.getPath().value();
        int i = getRootContextPathIndex(path);
        if(i < 0) {
            return isGraphQlAuthorized((JwtAuthenticationToken) auth, path);
        }
        path = getContextPath(path, i);
        if (isQueryOrSearch(method, path))
            return isAuthorized((JwtAuthenticationToken) auth, Role.READER, Role.CONTRIBUTOR);
        if(isAudit(path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.AUDITOR);
        }
        if(isInsertOrUpdateAccess(method, path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR);
        }
        return isCustomAccessAuthorized(auth, context, method, path);
    }

    protected int getRootContextPathIndex(String path) {
        return path.indexOf(RestApiVersion.API_PREFIX);
    }

    protected String getContextPath(String path, int startIndex) {
        return path.substring(startIndex + RestApiVersion.API_PREFIX.length());
    }

    protected boolean isGraphQlAuthorized(JwtAuthenticationToken auth, String path) {
        return isGraphQlSearch(graphQlBaseUrl, path) && isAuthorized(auth, Role.READER, Role.CONTRIBUTOR);
    }

    protected boolean isCustomAccessAuthorized(Authentication auth, AuthorizationContext context, HttpMethod method, String path) {
        return false;
    }

    protected boolean isAuthorized(JwtAuthenticationToken auth, Role... requiredRoles) {
        /*if(roleTokenLookup == RoleTokenLookup.AUTHORITIES) {
            return isInAuthorities(auth, requiredRoles);
        }*/
        if(roleTokenLookup == RoleTokenLookup.GROUPS) {
            return isInOneOfGroups(auth, requiredRoles);
        }
        return isInOneOfRealmRoles(auth, requiredRoles);
    }

    protected boolean isInOneOfGroups(JwtAuthenticationToken auth, Role[] requiredRoles) {
        List<String> groups = (List<String>) auth.getTokenAttributes().get(GROUPS);
        if(groups != null && !groups.isEmpty()) {
            return groups.stream().filter(g->g.startsWith(FileConstants.SLASH + rootGroupName + FileConstants.SLASH))
                    .map(g->g.substring(rootGroupName.length() + 2))
                    .anyMatch(g -> Arrays.stream(requiredRoles).anyMatch(r->r.toString().equals(g)));
        }
        return false;
    }

    protected boolean isInOneOfRealmRoles(JwtAuthenticationToken auth, Role... requiredRoles) {
        List<String> accessRoles = getAccessRoles(auth);
        return accessRoles != null && !accessRoles.isEmpty() && Arrays.stream(requiredRoles)
                .anyMatch(requiredRole -> accessRoles.contains(requiredRole.toString()));
    }

    protected List<String> getAccessRoles(JwtAuthenticationToken auth) {
        return getRealmAccessRoles(auth);
    }

    protected List<String> getRealmAccessRoles(JwtAuthenticationToken auth) {
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

    protected boolean isDeleteAccess(ServerHttpRequest request) {
        return request.getMethod().equals(HttpMethod.DELETE);
    }

    protected final boolean isInsertOrUpdateAccess(HttpMethod method, String path) {
        return ((method.equals(HttpMethod.PATCH) || method.equals(HttpMethod.PUT))
                && pathStartsWith(path, "/files", "/folders", "/documents")) ||
                (method.equals(HttpMethod.POST) && (
                        pathStartsWith(path, "/files", "/documents/upload", "/documents/upload-multiple") ||
                                path.equals("/folders") ||
                                path.equals("/folders/move") ||
                                path.equals("/folders/copy")));
    }

    protected final boolean isAudit(String path) {
        return pathStartsWith(path, "/audit");
    }

    /**
     * All GET methods and all POST methods used for search and query
     * */
    protected final boolean isQueryOrSearch(HttpMethod method, String path) {
        return (method.equals(HttpMethod.GET)
                && pathStartsWith(path, "/files", "/folders", "/documents"))
                ||
                (method.equals(HttpMethod.POST) && (
                        pathStartsWith(path, "/documents/download-multiple", "/documents/search/ids-by-metadata", "/folders/list")
                                || (path.startsWith("/documents/") && path.endsWith("/search/metadata")))
                );
    }

    protected boolean pathStartsWith(String path, String... contextPaths) {
        return Arrays.stream(contextPaths).anyMatch(contextPath -> pathStartsWith(path, contextPath));
    }

    protected boolean pathStartsWith(String path, String contextPath) {
        return path.equals(contextPath) || path.startsWith(contextPath + FileConstants.SLASH);
    }

    protected boolean isGraphQlSearch(String baseUrl, String path) {
        return path.contains(baseUrl);
    }

}
