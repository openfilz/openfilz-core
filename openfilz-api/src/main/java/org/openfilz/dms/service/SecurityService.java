package org.openfilz.dms.service;

import org.springframework.security.core.Authentication;
import org.springframework.security.web.server.authorization.AuthorizationContext;

public interface SecurityService {

    boolean authorize(Authentication auth, AuthorizationContext context);

}
