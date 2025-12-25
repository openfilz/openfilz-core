package org.openfilz.dms.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Authentication token for OnlyOffice DocumentServer requests.
 * Contains user information extracted from the HS256 JWT access token.
 */
public class OnlyOfficeAuthenticationToken extends AbstractAuthenticationToken {

    private final String userId;
    private final String userName;
    private final UUID documentId;
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
        super(createAuthorities());
        this.userId = userId;
        this.userName = userName;
        this.documentId = documentId;
        this.rawToken = rawToken;
        setAuthenticated(true);
    }

    private static Collection<? extends GrantedAuthority> createAuthorities() {
        // OnlyOffice requests get READER role for document access
        return List.of(
                new SimpleGrantedAuthority("ROLE_ONLYOFFICE"),
                new SimpleGrantedAuthority("ROLE_READER")
        );
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

    public String getUserId() {
        return userId;
    }

    public String getUserName() {
        return userName;
    }

    public UUID getDocumentId() {
        return documentId;
    }

    public String getRawToken() {
        return rawToken;
    }
}
