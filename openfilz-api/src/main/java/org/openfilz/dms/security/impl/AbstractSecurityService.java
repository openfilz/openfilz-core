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
import java.util.HashSet;
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

    /**
     * Runtime toggle ({@code openfilz.signature.require-requester-role}): when on, e-Sign
     * writes additionally require {@link Role#SIGN_REQUESTER}. Off by default so realms
     * that predate the role keep working unchanged.
     */
    @Value("${openfilz.signature.require-requester-role:false}")
    protected boolean requireSignatureRequesterRole;

    /**
     * Runtime toggle ({@code openfilz.workflows.require-designer-role}): when on, workflow
     * definition writes additionally require {@link Role#WORKFLOW_DESIGNER}.
     */
    @Value("${openfilz.workflows.require-designer-role:false}")
    protected boolean requireWorkflowDesignerRole;

    protected final AutorizationMode autorizationMode;
    protected final OnlyOfficeProperties onlyOfficeProperties;
    protected final ThumbnailProperties thumbnailProperties;

    public boolean authorize(Authentication auth, AuthorizationContext context) {
        ServerHttpRequest request = context.getExchange().getRequest();
        HttpMethod method = request.getMethod();
        String fullPath = request.getPath().value();
        int idx = getRootContextPathIndex(fullPath);
        // AI endpoints: accessible to READER, CONTRIBUTOR, and CLEANER (for delete)
        if (idx >= 0 && getContextPath(fullPath, idx).startsWith(RestApiVersion.ENDPOINT_AI)) {
            return isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString(), Role.CLEANER.toString()));
        }
        // Per-user AI settings (BYOK) - /settings/ai**. Self-scoped: the controller keys the row on
        // the email in the JWT and never accepts a userId from the request, so every user who may
        // use the assistant may read and write their own row - hence the same role set as the
        // /ai branch above. Needs its own branch (and must sit above the DELETE check): only GET
        // falls into isQueryOrSearch, while PUT (save), POST (/test, /models) and DELETE (reset)
        // match no generic rule and would otherwise be denied.
        if (idx >= 0 && pathStartsWith(getContextPath(fullPath, idx), RestApiVersion.ENDPOINT_SETTINGS + RestApiVersion.ENDPOINT_AI)) {
            return isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString(), Role.CLEANER.toString()));
        }
        // e-Sign: initiators (CONTRIBUTOR) create/send/cancel/resend envelopes and own templates (incl. DELETE of
        // their own templates); READER may list what waits for their signature. The signer-facing
        // /public/signatures/** path never reaches here (dedicated permit-all chain).
        if (idx >= 0 && isSignature(getContextPath(fullPath, idx))) {
            return isSignatureAuthorized(auth, method, getContextPath(fullPath, idx));
        }
        // Workflows: reads for READER/CONTRIBUTOR; completing a task only needs to be a candidate (the
        // service checks); definition writes, start, cancel and reassign need CONTRIBUTOR. Sits above the
        // DELETE check so a CONTRIBUTOR may delete their own definitions.
        if (idx >= 0 && isWorkflow(getContextPath(fullPath, idx))) {
            return isWorkflowAuthorized(auth, method, getContextPath(fullPath, idx));
        }
        if(isDeleteAccess(request)) {
            return isAuthorized((JwtAuthenticationToken) auth, Role.CLEANER.toString());
        }
        String path = fullPath;
        int i = idx;
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
        return thumbnailProperties.isActive() && (method.equals(HttpMethod.GET) || method.equals(HttpMethod.HEAD)) && path.startsWith(ENDPOINT_THUMBNAILS + "/img/");
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

    /** All-of counterpart of {@link #isAuthorized(JwtAuthenticationToken, List)} — same group/realm-role duality. */
    protected boolean hasAllRoles(JwtAuthenticationToken auth, List<String> requiredRoles) {
        if (autorizationMode.areRolesBasedOnGroups()) {
            return isInAllGroups(auth, requiredRoles);
        }
        return isInAllRealmRoles(auth, requiredRoles);
    }

    protected boolean isInAllGroups(JwtAuthenticationToken auth, List<String> requiredRoles) {
        List<String> groups = auth.getToken().getClaim(GROUPS);
        if (groups != null && !groups.isEmpty()) {
            return requiredRoles.stream().allMatch(r -> isInGroups(r, groups));
        }
        return false;
    }

    protected boolean isInAllRealmRoles(JwtAuthenticationToken auth, List<String> requiredRoles) {
        Map<String, Object> realmAccess = auth.getToken().getClaim(REALM_ACCESS);
        if (!CollectionUtils.isEmpty(realmAccess)) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) realmAccess.getOrDefault(ROLES, Collections.emptyList());
            return new HashSet<>(roles).containsAll(requiredRoles);
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
                        pathStartsWith(path, RestApiVersion.ENDPOINT_FILES, "/documents/upload", "/documents/upload-multiple", RestApiVersion.ENDPOINT_RECYCLE_BIN, RestApiVersion.ENDPOINT_PDF) ||
                                path.equals(RestApiVersion.ENDPOINT_FOLDERS) ||
                                path.equals("/folders/move") ||
                                path.equals("/folders/copy") ||
                                path.equals("/documents/create-blank") ||
                                isVersionRestore(path)
                        ));
    }

    /**
     * POST /documents/{id}/versions/{versionId}/restore — a content write, CONTRIBUTOR only.
     * Not whitelisted in WORM mode (WormSecurityServiceImpl overrides isInsertOrUpdateAccess).
     */
    protected final boolean isVersionRestore(String path) {
        return path.startsWith(RestApiVersion.ENDPOINT_DOCUMENTS + FileConstants.SLASH)
                && path.contains("/versions/") && path.endsWith("/restore");
    }

    /**
     * e-Sign authorisation hook. Core: GET for READER/CONTRIBUTOR, everything else CONTRIBUTOR —
     * plus {@link Role#SIGN_REQUESTER} when {@link #requireSignatureRequesterRole} is on, so a
     * deployment can restrict who may initiate signature requests. GETs are never gated by the
     * requester role: recipients without it must still list what waits for their signature.
     * Editions with a richer role model (e.g. share permissions) override this.
     */
    protected boolean isSignatureAuthorized(Authentication auth, HttpMethod method, String path) {
        if (method.equals(HttpMethod.GET)) {
            return isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString()));
        }
        if (requireSignatureRequesterRole) {
            return hasAllRoles((JwtAuthenticationToken) auth, of(Role.CONTRIBUTOR.toString(), Role.SIGN_REQUESTER.toString()));
        }
        return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR.toString());
    }

    protected final boolean isSignature(String path) {
        return pathStartsWith(path, RestApiVersion.ENDPOINT_SIGNATURES, RestApiVersion.ENDPOINT_SIGNATURE_TEMPLATES);
    }

    /**
     * Workflows authorisation hook (docs/workflows.md §7). GET → READER/CONTRIBUTOR; completing a
     * task (POST /tasks/{id}/complete) → READER/CONTRIBUTOR too, the engine binds it to the
     * candidate list; definition writes → CONTRIBUTOR (+ {@link Role#WORKFLOW_DESIGNER} when
     * {@link #requireWorkflowDesignerRole}); everything else (start, cancel, reassign, validate) → CONTRIBUTOR.
     */
    protected boolean isWorkflowAuthorized(Authentication auth, HttpMethod method, String path) {
        if (method.equals(HttpMethod.GET) || isWorkflowTaskCompletion(path)) {
            return isAuthorized((JwtAuthenticationToken) auth, of(Role.READER.toString(), Role.CONTRIBUTOR.toString()));
        }
        if (requireWorkflowDesignerRole && isWorkflowDefinitionWrite(path)) {
            return hasAllRoles((JwtAuthenticationToken) auth, of(Role.CONTRIBUTOR.toString(), Role.WORKFLOW_DESIGNER.toString()));
        }
        return isAuthorized((JwtAuthenticationToken) auth, Role.CONTRIBUTOR.toString());
    }

    protected final boolean isWorkflow(String path) {
        return pathStartsWith(path, RestApiVersion.ENDPOINT_WORKFLOWS);
    }

    protected final boolean isWorkflowTaskCompletion(String path) {
        return path.startsWith(RestApiVersion.ENDPOINT_WORKFLOWS + "/tasks/") && path.endsWith("/complete");
    }

    protected final boolean isWorkflowDefinitionWrite(String path) {
        return path.startsWith(RestApiVersion.ENDPOINT_WORKFLOWS + "/definitions") && !path.endsWith("/validate");
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
                    RestApiVersion.ENDPOINT_SETTINGS,
                    RestApiVersion.ENDPOINT_PDF
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
