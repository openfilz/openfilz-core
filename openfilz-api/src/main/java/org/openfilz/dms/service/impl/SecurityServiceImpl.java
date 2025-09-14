package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.enums.Role;
import org.openfilz.dms.enums.RoleTokenLookup;
import org.openfilz.dms.utils.FileConstants;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.server.authorization.AuthorizationContext;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class SecurityServiceImpl extends AbstractSecurityService {

    public SecurityServiceImpl(RoleTokenLookup roleTokenLookup, String rootGroupName, String graphQlBaseUrl) {
        super(roleTokenLookup, rootGroupName, graphQlBaseUrl);
    }

}
