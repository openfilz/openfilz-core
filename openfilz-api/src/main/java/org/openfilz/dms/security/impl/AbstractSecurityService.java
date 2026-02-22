package org.openfilz.dms.security.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.config.AutorizationMode;
import org.openfilz.dms.security.SecurityService;
import org.openfilz.dms.utils.FileConstants;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;
import org.springframework.util.CollectionUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.List.of;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_ONLYOFFICE;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

@Slf4j
@RequiredArgsConstructor
public abstract class AbstractSecurityService implements SecurityService {

    protected static final String ROLES = "roles";
    protected static final String REALM_ACCESS = "realm_access";
    protected static final String GROUPS = "groups";

    @Value("${spring.graphql.http.path:/graphql}")
    protected String graphQlBaseUrl;

    protected final AutorizationMode autorizationMode;
    protected final OnlyOfficeProperties onlyOfficeProperties;
    protected final ThumbnailProperties thumbnailProperties;

    public boolean authorize(Authentication auth, AuthorizationContext context) {
        ServerHttpRequest request = context.getExchange().getRequest();
        HttpMethod method = request.getMethod();
        if(isDeleteAccess(request)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CLEANER.toString());
        }
        String path = request.getPath().value();
        int i = getRootContextPathIndex(path);
        if(i < 0) {
            return isGraphQlAuthorized((JwtAuthenticationToken) auth, path);
        }
        path = getContextPath(path, i);
        if(isThumbnail(method, path) || isQueryOrSearch(method, path))
            return isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString()));
        if(isAudit(path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.AUDITOR.toString());
        }
        if(isInsertOrUpdateAccess(method, path)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR.toString());
        }
        if(isOnlyOffice(method, path)) {
            if(path.startsWith("/onlyoffice/config/")) {
                List<String> edit = request.getQueryParams().get("canEdit");
                if(!CollectionUtils.isEmpty(edit)) {
                    boolean canEdit = Boolean.parseBoolean(edit.getFirst());
                    if(canEdit) {
                        return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR.toString());
                    }
                }
                boolean authorized = isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString()));
                log.debug("path {} - authorized {}", path, authorized);
                return authorized;
            }
            return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR.toString());
        }
        return isCustomAccessAuthorized(auth, context, method, path);
    }

    private boolean isThumbnail(HttpMethod method, String path) {
        return thumbnailProperties.isActive() && method.equals(HttpMethod.GET) && path.startsWith(ENDPOINT_THUMBNAILS + "/img/");
    }

    private boolean isOnlyOffice(HttpMethod method, String path) {
        return onlyOfficeProperties.isEnabled()
                && (method.equals(HttpMethod.GET) || method.equals(HttpMethod.POST))
                && pathStartsWith(path, ENDPOINT_ONLYOFFICE);
    }

    protected int getRootContextPathIndex(String path) {
        return path.indexOf(RestApiVersion.API_PREFIX);
    }

    protected String getContextPath(String path, int startIndex) {
        return path.substring(startIndex + RestApiVersion.API_PREFIX.length());
    }

    protected boolean isGraphQlAuthorized(JwtAuthenticationToken auth, String path) {
        return isGraphQlSearch(graphQlBaseUrl, path) && isAuthorized(auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString()));
    }

    protected boolean isCustomAccessAuthorized(Authentication auth, AuthorizationContext context, HttpMethod method, String path) {
        return false;
    }

    @Override
    public boolean isAuthorized(JwtAuthenticationToken auth, List<String> anyRoles) {
        if(autorizationMode.areRolesBasedOnGroups()) {
            return isInOneOfGroups(auth, anyRoles);
        }
        return isInOneOfRealmRoles(auth, anyRoles);
    }

    @Override
    public boolean isAuthorized(JwtAuthenticationToken auth, String role) {
        if(autorizationMode.areRolesBasedOnGroups()) {
            return hasGroup(auth, role);
        }
        return hasRealmRole(auth, role);
    }

    private boolean hasGroup(JwtAuthenticationToken auth, String groupSuffix) {
        List<String> groups = auth.getToken().getClaim(GROUPS);
        if(groups != null && !groups.isEmpty()) {
            String group = FileConstants.SLASH + autorizationMode.getRootGroupName() + FileConstants.SLASH + groupSuffix;
            return groups.contains(group);
        }
        return false;
    }

    private boolean hasRealmRole(JwtAuthenticationToken auth, String role) {
        Map<String, Object> realmAccess = auth.getToken().getClaim(REALM_ACCESS);
        if (!CollectionUtils.isEmpty(realmAccess)) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.getOrDefault(ROLES, Collections.emptyList());
            return roles.contains(role);
        }
        return false;
    }

    protected boolean isInGroups(String groupSuffix, List<String> groups) {
        String group = FileConstants.SLASH + autorizationMode.getRootGroupName() + FileConstants.SLASH + groupSuffix;
        return groups.contains(group);
    }

    protected boolean isInOneOfGroups(JwtAuthenticationToken auth, List<String> requiredRoles) {
        List<String> groups = auth.getToken().getClaim(GROUPS);
        if(groups != null && !groups.isEmpty()) {
            return requiredRoles.stream().anyMatch(r->isInGroups(r, groups));
        }
        return false;
    }

    protected boolean isInOneOfRealmRoles(JwtAuthenticationToken auth, List<String> requiredRoles) {
        Map<String, Object> realmAccess = auth.getToken().getClaim(REALM_ACCESS);
        if (!CollectionUtils.isEmpty(realmAccess)) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.getOrDefault(ROLES, Collections.emptyList());
            return requiredRoles.stream().anyMatch(roles::contains);
        }
        return false;
    }

    protected boolean isDeleteAccess(ServerHttpRequest request) {
        return request.getMethod().equals(HttpMethod.DELETE);
    }

    protected boolean isInsertOrUpdateAccess(HttpMethod method, String path) {
        return ((method.equals(HttpMethod.PATCH) || method.equals(HttpMethod.PUT))
                && pathStartsWith(path, RestApiVersion.ENDPOINT_FILES, RestApiVersion.ENDPOINT_FOLDERS, RestApiVersion.ENDPOINT_DOCUMENTS)) ||
                ((!method.equals(HttpMethod.TRACE) && !method.equals(HttpMethod.PUT)) && pathStartsWith(path, "/tus")) ||
                (method.equals(HttpMethod.POST) && (
                        pathStartsWith(path, RestApiVersion.ENDPOINT_FILES, "/documents/upload", "/documents/upload-multiple", RestApiVersion.ENDPOINT_RECYCLE_BIN) ||
                                path.equals(RestApiVersion.ENDPOINT_FOLDERS) ||
                                path.equals("/folders/move") ||
                                path.equals("/folders/copy") ||
                                path.equals("/documents/create-blank")
                        ));
    }

    protected final boolean isAudit(String path) {
        return pathStartsWith(path, "/audit");
    }

    /**
     * All GET methods and all POST methods used for search and query
     * */
    protected final boolean isQueryOrSearch(HttpMethod method, String path) {
        return (method.equals(HttpMethod.GET)
                && pathStartsWith(path, RestApiVersion.ENDPOINT_FILES,
                    RestApiVersion.ENDPOINT_FOLDERS,
                    RestApiVersion.ENDPOINT_DOCUMENTS,
                    RestApiVersion.ENDPOINT_SUGGESTIONS,
                    RestApiVersion.ENDPOINT_RECYCLE_BIN,
                    RestApiVersion.ENDPOINT_DASHBOARD,
                    RestApiVersion.ENDPOINT_FAVORITES,
                    RestApiVersion.ENDPOINT_SETTINGS
                ))
                ||
                (method.equals(HttpMethod.POST) && (
                        pathStartsWith(path, "/documents/download-multiple", "/documents/search/ids-by-metadata", "/folders/list", RestApiVersion.ENDPOINT_FAVORITES)
                                || (path.startsWith("/documents/") && path.endsWith("/search/metadata")))
                )
                ||
                (method.equals(HttpMethod.PUT) && pathStartsWith(path, RestApiVersion.ENDPOINT_FAVORITES))
                ;
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
