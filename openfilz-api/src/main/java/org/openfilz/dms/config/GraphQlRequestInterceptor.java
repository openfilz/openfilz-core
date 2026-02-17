package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.security.JwtTokenParser;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Component
@Slf4j
public class GraphQlRequestInterceptor implements WebGraphQlInterceptor {

    private final JwtTokenParser jwtTokenParser;
    private final boolean graphiqlEnabled;

    public GraphQlRequestInterceptor(JwtTokenParser jwtTokenParser,
                                     @Value("${spring.graphql.graphiql.enabled:false}") boolean graphiqlEnabled) {
        this.jwtTokenParser = jwtTokenParser;
        this.graphiqlEnabled = graphiqlEnabled;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        // Skip JWT parsing for introspection queries only when GraphiQL is enabled
        if (graphiqlEnabled && isIntrospectionQuery(request)) {
            return chain.next(request);
        }
        return jwtTokenParser.addUserInfoToContext(request, chain);
    }

    private boolean isIntrospectionQuery(WebGraphQlRequest request) {
        String document = request.getDocument();
        return document != null && (document.contains("__schema")
                || document.contains("__type(")
                || document.contains("IntrospectionQuery"));
    }
}
