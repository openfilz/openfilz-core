package org.openfilz.dms.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.util.List;

public interface SecurityService {

    boolean authorize(Authentication auth, AuthorizationContext context);

    boolean isAuthorized(JwtAuthenticationToken auth, String role);

    boolean isAuthorized(JwtAuthenticationToken auth, List<String> anyRoles);
}
