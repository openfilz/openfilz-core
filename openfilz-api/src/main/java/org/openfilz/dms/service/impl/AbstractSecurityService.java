package org.openfilz.dms.service.impl;

import org.openfilz.dms.service.SecurityService;
import org.openfilz.dms.utils.FileConstants;
import org.springframework.http.HttpMethod;

import java.util.Arrays;

public abstract class AbstractSecurityService implements SecurityService {

    protected final boolean isDeleteAccess(HttpMethod method) {
        return method.equals(HttpMethod.DELETE);
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

    private boolean pathStartsWith(String path, String... contextPaths) {
        return Arrays.stream(contextPaths).anyMatch(contextPath -> pathStartsWith(path, contextPath));
    }

    private boolean pathStartsWith(String path, String contextPath) {
        return path.equals(contextPath) || path.startsWith(contextPath + FileConstants.SLASH);
    }

    protected boolean isGraphQlSearch(String baseUrl, String path) {
        return path.contains(baseUrl);
    }

}
