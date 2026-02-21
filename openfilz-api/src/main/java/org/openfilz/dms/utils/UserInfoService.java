package org.openfilz.dms.utils;

import org.openfilz.dms.security.OnlyOfficeAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.security.JwtTokenParser.EMAIL;

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

    default Mono<String> getConnectedUserEmail() {
        return getAuthenticationMono()
                .map(this::getUserEmail)
                .switchIfEmpty(Mono.just(ANONYMOUS_USER));
    }

    private String getUserEmail(Authentication auth) {
        if(auth instanceof OnlyOfficeAuthenticationToken jwt) {
            return jwt.getUserEmail() != null ? jwt.getUserEmail() : jwt.getUserId();
        }
        return getUserAttribute(auth, EMAIL);
    }

    default Mono<Authentication> getAuthenticationMono() {
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication);
    }
}