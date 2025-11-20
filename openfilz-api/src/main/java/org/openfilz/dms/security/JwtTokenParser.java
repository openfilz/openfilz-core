package org.openfilz.dms.security;

import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import reactor.core.publisher.Mono;

public interface JwtTokenParser {

    String EMAIL = "email";

    Mono<WebGraphQlResponse> addUserInfoToContext(WebGraphQlRequest request, WebGraphQlInterceptor.Chain chain);

}
