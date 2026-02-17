package org.openfilz.dms.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/**
 * WebFilter that detects GraphQL introspection queries and marks them
 * so the security chain can allow them through without authentication.
 * <p>
 * This is analogous to how Swagger UI works: the OpenAPI spec at /v3/api-docs
 * is publicly accessible, while actual API calls require authentication.
 * Similarly, GraphQL schema introspection is allowed without auth,
 * while data queries still require a valid JWT token.
 */
@Component
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.security.no-auth", havingValue = "false"),
        @ConditionalOnProperty(name = "spring.graphql.graphiql.enabled", havingValue = "true")
})
@Slf4j
public class GraphQlIntrospectionFilter implements WebFilter, Ordered {

    public static final String GRAPHQL_INTROSPECTION_ATTRIBUTE = "GRAPHQL_INTROSPECTION";

    // Pre-computed ASCII byte patterns for zero-allocation scanning.
    // Safe to match against UTF-8 content since ASCII bytes are identical in UTF-8.
    private static final byte[] SCHEMA_MARKER = "__schema".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TYPE_MARKER = "__type".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] INTROSPECTION_MARKER = "IntrospectionQuery".getBytes(StandardCharsets.US_ASCII);

    private final String graphQlPath;

    public GraphQlIntrospectionFilter(@Value("${spring.graphql.http.path:/graphql}") String graphQlPath) {
        this.graphQlPath = graphQlPath;
        log.info("GraphQlIntrospectionFilter created - graphQlPath={}", graphQlPath);
    }

    @Override
    public int getOrder() {
        // Run before the security filters
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();

        // Only process POST requests to the GraphQL endpoint
        if (!HttpMethod.POST.equals(request.getMethod())
                || !request.getPath().value().equals(graphQlPath)) {
            return chain.filter(exchange);
        }

        log.debug("Processing POST {} - path matches graphQlPath={}", request.getPath().value(), graphQlPath);

        return DataBufferUtils.join(request.getBody())
                .flatMap(dataBuffer -> {
                    boolean isIntrospection = containsIntrospectionMarker(dataBuffer);
                    log.debug("Introspection marker detected: {}", isIntrospection);
                    if (isIntrospection) {
                        exchange.getAttributes().put(GRAPHQL_INTROSPECTION_ATTRIBUTE, Boolean.TRUE);
                    }

                    // Replay the consumed body for downstream consumption
                    ServerHttpRequest replayedRequest = new ServerHttpRequestDecorator(request) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            return Flux.just(dataBuffer);
                        }
                    };

                    // Use ServerWebExchangeDecorator instead of exchange.mutate() to
                    // avoid StrictServerWebExchangeFirewall incompatibility
                    // (spring-security#16002) where mutated exchanges get
                    // ReadOnlyHttpHeaders on the response, causing
                    // UnsupportedOperationException when the security chain sets
                    // WWW-Authenticate on a 401 response.
                    return chain.filter(new ServerWebExchangeDecorator(exchange) {
                        @Override
                        public ServerHttpRequest getRequest() {
                            return replayedRequest;
                        }
                    });
                })
                .switchIfEmpty(chain.filter(exchange));
    }

    /**
     * Scans the DataBuffer directly for introspection markers using
     * {@link DataBuffer#readPosition(int)} and {@link DataBuffer#read()} —
     * no intermediate ByteBuffer, byte[] or String allocation needed.
     * The read position is restored after scanning so the buffer can be replayed downstream.
     */
    private static boolean containsIntrospectionMarker(DataBuffer dataBuffer) {
        int startPos = dataBuffer.readPosition();
        int readable = dataBuffer.readableByteCount();
        boolean found = containsPattern(dataBuffer, startPos, readable, SCHEMA_MARKER)
                || containsPattern(dataBuffer, startPos, readable, TYPE_MARKER)
                || containsPattern(dataBuffer, startPos, readable, INTROSPECTION_MARKER);
        dataBuffer.readPosition(startPos);
        return found;
    }

    /**
     * Naive byte-pattern scan directly on the DataBuffer (sufficient for small GraphQL payloads).
     */
    private static boolean containsPattern(DataBuffer buffer, int startPos, int readable, byte[] pattern) {
        int searchLimit = readable - pattern.length;
        for (int i = 0; i <= searchLimit; i++) {
            buffer.readPosition(startPos + i);
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (buffer.read() != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return true;
            }
        }
        return false;
    }
}
