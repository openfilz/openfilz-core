package org.openfilz.dms.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

/**
 * Authentication manager for OnlyOffice requests.
 * Validates the HS256 JWT token and creates an authenticated token with user info.
 */
@Slf4j
@RequiredArgsConstructor
public class OnlyOfficeAuthenticationManager implements ReactiveAuthenticationManager {

    private final OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService;

    @Override
    public Mono<Authentication> authenticate(Authentication authentication) {
        if (!(authentication instanceof OnlyOfficeAuthenticationToken token)) {
            return Mono.empty();
        }

        String rawToken = token.getRawToken();
        if (rawToken == null || rawToken.isEmpty()) {
            return Mono.error(new BadCredentialsException("Missing OnlyOffice access token"));
        }

        Map<String, Object> claims = jwtService.validateAndDecode(rawToken);
        // Validate token
        if (claims == null) {
            log.warn("Invalid OnlyOffice access token");
            return Mono.error(new BadCredentialsException("Invalid OnlyOffice access token"));
        }

        // Extract user info from token
        UUID documentId = jwtService.extractDocumentId(claims);
        String userId = jwtService.extractUserId(claims);
        String userName = jwtService.extractUserName(claims);

        if (documentId == null) {
            log.warn("OnlyOffice token missing document ID");
            return Mono.error(new BadCredentialsException("OnlyOffice token missing document ID"));
        }

        if (userId == null) {
            log.warn("OnlyOffice token missing user ID");
            return Mono.error(new BadCredentialsException("OnlyOffice token missing user ID"));
        }

        log.debug("OnlyOffice authentication successful for user {} on document {}", userId, documentId);

        // Return authenticated token
        return Mono.just(new OnlyOfficeAuthenticationToken(userId, userName, documentId, rawToken));
    }
}
