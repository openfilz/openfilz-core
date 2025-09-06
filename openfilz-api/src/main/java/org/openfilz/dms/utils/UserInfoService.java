// com/example/dms/utils/UserPrincipalExtractor.java
package org.openfilz.dms.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.utils.JwtTokenParser.EMAIL;

@Service
public class UserInfoService {

    private static final String ANONYMOUS_USER = "anonymousUser";

    private String getUserEmail(Authentication authentication) {
        if (authentication == null) {
            return ANONYMOUS_USER;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt) {
            return ((Jwt) principal).getClaimAsString(EMAIL);
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            return ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthToken) {
            return jwtAuthToken.getToken().getClaimAsString(EMAIL);
        }
        return authentication.getName();
    }

    public Mono<String> getConnectedUser(Authentication auth) {
        return Mono.just(getUserEmail(auth));
    }
}