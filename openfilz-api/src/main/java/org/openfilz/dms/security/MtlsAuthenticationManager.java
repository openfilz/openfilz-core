package org.openfilz.dms.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.regex.Pattern;

/**
 * Authentication manager for mTLS requests.
 * Validates that the client certificate's subject DN matches the allowed pattern.
 */
@Slf4j
@RequiredArgsConstructor
public class MtlsAuthenticationManager implements ReactiveAuthenticationManager {

    private final String allowedDnPattern;
    private Pattern compiledPattern;

    private Pattern getCompiledPattern() {
        if (compiledPattern == null && allowedDnPattern != null) {
            compiledPattern = Pattern.compile(allowedDnPattern);
        }
        return compiledPattern;
    }

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!(authentication instanceof MtlsAuthenticationToken token)) {
            return Mono.empty();
        }

        String subjectDn = token.getSubjectDn();
        if (subjectDn == null || subjectDn.isEmpty()) {
            log.warn("mTLS authentication failed: no subject DN in certificate");
            return Mono.error(new BadCredentialsException("Invalid client certificate"));
        }

        // Validate DN against allowed pattern
        Pattern pattern = getCompiledPattern();
        if (pattern != null && !pattern.matcher(subjectDn).matches()) {
            log.warn("mTLS authentication failed: DN '{}' does not match allowed pattern '{}'",
                    subjectDn, allowedDnPattern);
            return Mono.error(new BadCredentialsException("Client certificate not authorized"));
        }

        log.debug("mTLS authentication successful for DN: {}", subjectDn);

        // Return authenticated token
        return Mono.just(new MtlsAuthenticationToken(token.getCertificate(), subjectDn));
    }
}
