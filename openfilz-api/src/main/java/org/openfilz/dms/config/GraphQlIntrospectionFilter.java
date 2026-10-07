package org.openfilz.dms.config;

import graphql.language.Definition;
import graphql.language.Document;
import graphql.language.Field;
import graphql.language.FragmentDefinition;
import graphql.language.OperationDefinition;
import graphql.language.Selection;
import graphql.parser.Parser;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

/**
 * WebFilter that detects GraphQL introspection queries and marks them
 * so the security chain can allow them through without authentication.
 * <p>
 * This is analogous to how Swagger UI works: the OpenAPI spec at /v3/api-docs
 * is publicly accessible, while actual API calls require authentication.
 * Similarly, GraphQL schema introspection is allowed without auth,
 * while data queries still require a valid JWT token.
 * <p>
 * <b>Security.</b> The request is only marked as introspection when the GraphQL
 * document actually parses and <em>every</em> operation is a {@code query} whose
 * top-level selections are exclusively the meta-fields {@code __schema},
 * {@code __type} or {@code __typename}. A document that merely <em>contains</em>
 * one of those words (for example {@code query IntrospectionQuery { listFolder(...) }})
 * is not introspection and stays authenticated. Fragment definitions are allowed
 * (the standard IntrospectionQuery uses them) but top-level fragment spreads and
 * inline fragments are not, so a selection cannot be smuggled through them.
 * Unauthenticated bodies are also capped at {@link #MAX_UNAUTHENTICATED_BODY_BYTES}
 * so the filter cannot be used to buffer arbitrary payloads before authentication.
 */
@Component
@ConditionalOnProperties({
        @ConditionalOnProperty(name = "openfilz.security.no-auth", havingValue = "false"),
        @ConditionalOnProperty(name = "spring.graphql.graphiql.enabled", havingValue = "true")
})
@Slf4j
public class GraphQlIntrospectionFilter implements WebFilter, Ordered {

    public static final String GRAPHQL_INTROSPECTION_ATTRIBUTE = "GRAPHQL_INTROSPECTION";

    /** Introspection documents are a few KB; anything larger is never introspection. */
    static final int MAX_UNAUTHENTICATED_BODY_BYTES = 64 * 1024;

    private static final Set<String> INTROSPECTION_FIELDS = Set.of("__schema", "__type", "__typename");

    private static final ObjectMapper JSON = new ObjectMapper();

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

        // Introspection queries from GraphiQL are unauthenticated.
        // Skip body consumption for authenticated requests to avoid
        // breaking context propagation through the ServerWebExchangeDecorator.
        if (request.getHeaders().containsHeader("Authorization")) {
            return chain.filter(exchange);
        }

        // The chain must run exactly once. Mono<Void> completes empty, so a plain
        // `.switchIfEmpty(chain.filter(exchange))` after the replaying branch would run the
        // downstream chain a second time; `handled` tells the two cases apart instead.
        return DataBufferUtils.join(request.getBody(), MAX_UNAUTHENTICATED_BODY_BYTES)
                .flatMap(dataBuffer -> {
                    if (isPureIntrospection(dataBuffer)) {
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
                    }).thenReturn(Boolean.TRUE);
                })
                .onErrorResume(DataBufferLimitException.class, e -> {
                    // An unauthenticated body this large is never an introspection query, and the
                    // partially consumed body cannot be replayed — answer 413 instead of buffering on.
                    log.warn("Rejected unauthenticated GraphQL body larger than {} bytes", MAX_UNAUTHENTICATED_BODY_BYTES);
                    exchange.getResponse().setStatusCode(HttpStatus.PAYLOAD_TOO_LARGE);
                    return exchange.getResponse().setComplete().thenReturn(Boolean.TRUE);
                })
                // No body at all: nothing to inspect, hand the untouched exchange on.
                .defaultIfEmpty(Boolean.FALSE)
                .flatMap(handled -> handled ? Mono.empty() : chain.filter(exchange));
    }

    /**
     * Decodes the buffered GraphQL HTTP body and decides whether it is a pure introspection
     * document. The read position is restored afterwards so the buffer can be replayed downstream.
     */
    private static boolean isPureIntrospection(DataBuffer dataBuffer) {
        int startPos = dataBuffer.readPosition();
        try {
            String body = dataBuffer.toString(StandardCharsets.UTF_8);
            return isPureIntrospectionRequest(body);
        } finally {
            dataBuffer.readPosition(startPos);
        }
    }

    /**
     * {@code true} only when {@code body} is a GraphQL-over-HTTP JSON object whose {@code query}
     * parses and consists solely of introspection meta-fields. Visible for tests.
     */
    static boolean isPureIntrospectionRequest(String body) {
        String query;
        try {
            JsonNode root = JSON.readTree(body);
            if (root == null || !root.isObject()) {
                return false;
            }
            JsonNode queryNode = root.get("query");
            if (queryNode == null || !queryNode.isString()) {
                return false;
            }
            query = queryNode.asString();
        } catch (RuntimeException e) {
            return false;
        }
        return isPureIntrospectionDocument(query);
    }

    /** Visible for tests. */
    static boolean isPureIntrospectionDocument(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        Document document;
        try {
            document = Parser.parse(query);
        } catch (RuntimeException e) {
            // InvalidSyntaxException and any other parser failure: not introspection.
            return false;
        }
        List<Definition> definitions = document.getDefinitions();
        boolean sawOperation = false;
        for (Definition<?> definition : definitions) {
            if (definition instanceof OperationDefinition operation) {
                if (operation.getOperation() != OperationDefinition.Operation.QUERY) {
                    return false;
                }
                if (operation.getSelectionSet() == null || operation.getSelectionSet().getSelections().isEmpty()) {
                    return false;
                }
                for (Selection<?> selection : operation.getSelectionSet().getSelections()) {
                    // Fragment spreads and inline fragments could hide a data field — refuse them at the top level.
                    if (!(selection instanceof Field field) || !INTROSPECTION_FIELDS.contains(field.getName())) {
                        return false;
                    }
                }
                sawOperation = true;
            } else if (!(definition instanceof FragmentDefinition)) {
                // Anything else (type system definitions, extensions…) is not an introspection request.
                return false;
            }
        }
        return sawOperation;
    }
}
