package org.openfilz.dms.security.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.security.JwtTokenParser;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

@Component
@ConditionalOnProperty(name = "openfilz.security.no-auth", havingValue = "true")
@RequiredArgsConstructor
public class NoAuthTokenParser implements JwtTokenParser {

    public Mono<WebGraphQlResponse> addUserInfoToContext(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain) {
        return chain.next(request)
                .contextWrite(Context.of(EMAIL, UserInfoService.ANONYMOUS_USER));
    }

}
