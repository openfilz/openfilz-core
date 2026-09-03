package org.openfilz.dms.security;

import lombok.Getter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.UUID;

/**
 * Authentication for a request redeeming a signed download link
 * ({@code GET /api/v1/documents/{id}/download?token=…}).
 * <p>
 * Once authenticated it represents the <em>minter</em> of the token, not an anonymous bearer:
 * {@link #getName()} is the minter's email, which is what {@code UserInfoService} falls back to —
 * so the audit trail attributes the download to the real user, and extension layers' secure DAO
 * overrides re-run their document-access check against that user at click time (instant
 * revocation, no capability-outlives-permission window).
 */
public class DownloadTokenAuthenticationToken extends AbstractAuthenticationToken {

    @Getter
    private final String minterEmail;

    /** The document named in the request path — the only one this authentication is valid for. */
    @Getter
    private final UUID documentId;

    @Getter
    private final String rawToken;

    /** Before validation: carries only what the converter extracted from the request. */
    public DownloadTokenAuthenticationToken(String rawToken, UUID documentId) {
        // Explicit cast: Spring Security 7 overloads this constructor, making a bare null ambiguous
        super((Collection<? extends GrantedAuthority>) null);
        this.rawToken = rawToken;
        this.documentId = documentId;
        this.minterEmail = null;
        setAuthenticated(false);
    }

    /** After validation: the minter's identity, established by the token service. */
    public DownloadTokenAuthenticationToken(String minterEmail, UUID documentId, String rawToken) {
        super((Collection<? extends GrantedAuthority>) null);
        this.minterEmail = minterEmail;
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
        return minterEmail;
    }

    @Override
    public String getName() {
        return minterEmail;
    }
}
