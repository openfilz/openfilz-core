package org.openfilz.dms.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.utils.JwtTokenParser;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@ConditionalOnProperty(name = "spring.security.no-auth", havingValue = "false")
@RequiredArgsConstructor
@Slf4j
public class GraphQlRequestInterceptor implements WebGraphQlInterceptor {

    private final JwtTokenParser jwtTokenParser;

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        return jwtTokenParser.addUserInfoToContext(request, chain);
    }
}