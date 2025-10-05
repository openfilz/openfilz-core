// com/example/dms/utils/UserPrincipalExtractor.java
package org.openfilz.dms.utils;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.utils.JwtTokenParser.EMAIL;

public interface UserInfoService {

    String ANONYMOUS_USER = "anonymousUser";

    default String getUserAttribute(Authentication authentication, String attribute) {
        if (authentication == null) {
            return ANONYMOUS_USER;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt) {
            return ((Jwt) principal).getClaimAsString(attribute);
        }
        if (principal instanceof org.springframework.security.core.userdetails.UserDetails) {
            return ((org.springframework.security.core.userdetails.UserDetails) principal).getUsername();
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuthToken) {
            return jwtAuthToken.getToken().getClaimAsString(attribute);
        }
        return authentication.getName();
    }

    default Mono<String> getConnectedUserEmail(Authentication auth) {
        return Mono.just(getUserAttribute(auth, EMAIL));
    }
}