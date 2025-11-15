package org.openfilz.dms.security.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.security.JwtTokenParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.security.no-auth", havingValue = "false"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
@RequiredArgsConstructor
public class DefaultJwtTokenParser implements JwtTokenParser {

    private static final Pattern authorizationPattern =
            Pattern.compile("^Bearer (?<token>[a-zA-Z0-9-._~+/]+=*)$", Pattern.CASE_INSENSITIVE);

    private static final String AUTHORIZATION = "Authorization";
    private static final String TOKEN = "token";
    private static final String BEARER = "bearer";


    protected final ReactiveJwtDecoder jwtDecoder;

    protected String extactTokenValue(HttpHeaders headers) {
        String authorizationValue = headers.getFirst(AUTHORIZATION);
        if(authorizationValue == null) {
            throw new AccessDeniedException("Authorization header missing");
        }
        if (!StringUtils.startsWithIgnoreCase(authorizationValue, BEARER)) {
            throw new AccessDeniedException("Not a bearer token");
        }

        Matcher matcher = authorizationPattern.matcher(authorizationValue);
        if (!matcher.matches()) {
            throw new AccessDeniedException("Bearer token is malformed");
        }

        return matcher.group(TOKEN);
    }

    public Mono<WebGraphQlResponse> addUserInfoToContext(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
        String token = extactTokenValue(request.getHeaders());
        return jwtDecoder.decode(token)
                .flatMap(jwt-> chain.next(request)
                        .contextWrite(Context.of(EMAIL, jwt.getClaimAsString(EMAIL))));
    }

}
