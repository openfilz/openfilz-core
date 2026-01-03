package org.openfilz.dms.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Authentication token for mTLS (mutual TLS) client certificate authentication.
 * Used to authenticate ImgProxy or other services accessing internal endpoints.
 */
public class MtlsAuthenticationToken extends AbstractAuthenticationToken {

    private final X509Certificate certificate;
    private final String subjectDn;

    /**
     * Creates an unauthenticated token with the client certificate.
     */
    public MtlsAuthenticationToken(X509Certificate certificate) {
        super(Collections.emptyList());
        this.certificate = certificate;
        this.subjectDn = certificate != null ? certificate.getSubjectX500Principal().getName() : null;
        setAuthenticated(false);
    }

    /**
     * Creates an authenticated token with the validated certificate.
     */
    public MtlsAuthenticationToken(X509Certificate certificate, String subjectDn) {
        super(Collections.singletonList(new SimpleGrantedAuthority("ROLE_MTLS_CLIENT")));
        this.certificate = certificate;
        this.subjectDn = subjectDn;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return certificate;
    }

    @Override
    public Object getPrincipal() {
        return subjectDn;
    }

    public X509Certificate getCertificate() {
        return certificate;
    }

    public String getSubjectDn() {
        return subjectDn;
    }
}
