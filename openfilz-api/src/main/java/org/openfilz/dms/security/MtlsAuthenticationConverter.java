package org.openfilz.dms.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.SslInfo;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.security.cert.X509Certificate;

/**
 * Converts mTLS requests to Authentication objects.
 * Extracts the client certificate from the SSL session.
 */
@Slf4j
public class MtlsAuthenticationConverter implements ServerAuthenticationConverter {

    @Override
    public Mono<Authentication> convert(ServerWebExchange exchange) {
        SslInfo sslInfo = exchange.getRequest().getSslInfo();

        if (sslInfo == null) {
            log.debug("No SSL info available in request");
            return Mono.empty();
        }

        X509Certificate[] peerCertificates = sslInfo.getPeerCertificates();
        if (peerCertificates == null || peerCertificates.length == 0) {
            log.debug("No client certificate presented");
            return Mono.empty();
        }

        // First certificate is the client certificate
        X509Certificate clientCert = peerCertificates[0];
        log.debug("Client certificate presented: {}", clientCert.getSubjectX500Principal().getName());

        return Mono.just(new MtlsAuthenticationToken(clientCert));
    }
}
