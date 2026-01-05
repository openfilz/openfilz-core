package org.openfilz.dms.security;

import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;

import java.util.UUID;

/**
 * Authentication token for OnlyOffice DocumentServer requests.
 * Contains user information extracted from the HS256 JWT access token.
 */
public class OnlyOfficeAuthenticationToken extends AbstractAuthenticationToken {

    @Getter
    private final String userId;

    private final String userName;

    @Getter
    private final UUID documentId;

    @Getter
    private final String rawToken;

    /**
     * Create an unauthenticated token (before validation).
     */
    public OnlyOfficeAuthenticationToken(String rawToken) {
        super(null);
        this.rawToken = rawToken;
        this.userId = null;
        this.userName = null;
        this.documentId = null;
        setAuthenticated(false);
    }

    /**
     * Create an authenticated token (after validation).
     */
    public OnlyOfficeAuthenticationToken(String userId, String userName, UUID documentId, String rawToken) {
        super(null);
        this.userId = userId;
        this.userName = userName;
        this.documentId = documentId;
        this.rawToken = rawToken;
        setAuthenticated(true);
    }



    @Override
    public Object getCredentials() {
        return rawToken;
    }

    @Override
    public Object getPrincipal() {
        return userId;
    }

    @Override
    public String getName() {
        return userName != null ? userName : userId;
    }

}
