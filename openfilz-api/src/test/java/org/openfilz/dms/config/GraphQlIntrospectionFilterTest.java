package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openfilz.dms.config.GraphQlIntrospectionFilter.GRAPHQL_INTROSPECTION_ATTRIBUTE;
import static org.openfilz.dms.config.GraphQlIntrospectionFilter.isPureIntrospectionDocument;
import static org.openfilz.dms.config.GraphQlIntrospectionFilter.isPureIntrospectionRequest;

/**
 * The anonymous bypass must only apply to documents that are nothing but introspection.
 * Before this check a request merely <em>containing</em> "IntrospectionQuery" or "__schema"
 * anywhere in its body ran its real resolvers without a token.
 */
class GraphQlIntrospectionFilterTest {

    private static final String GRAPHIQL_INTROSPECTION = """
            query IntrospectionQuery {
              __schema {
                queryType { name }
                mutationType { name }
                types { ...FullType }
              }
            }
            fragment FullType on __Type { kind name fields(includeDeprecated: true) { name } }
            """;

    @Test
    void standardIntrospectionQueryIsAccepted() {
        assertThat(isPureIntrospectionDocument(GRAPHIQL_INTROSPECTION)).isTrue();
        assertThat(isPureIntrospectionDocument("{ __schema { types { name } } }")).isTrue();
        assertThat(isPureIntrospectionDocument("query { __type(name: \"Document\") { name } __typename }")).isTrue();
    }

    @Test
    void dataQueryDisguisedAsIntrospectionIsRefused() {
        // The former marker scan accepted all of these.
        assertThat(isPureIntrospectionDocument(
                "query IntrospectionQuery { listFolder(request:{pageInfo:{pageNumber:1,pageSize:100}}) { id name } }"))
                .isFalse();
        assertThat(isPureIntrospectionDocument("{ __schema { queryType { name } } listAllFolder { id } }")).isFalse();
        assertThat(isPureIntrospectionDocument("query { __typename documentById(id: \"x\") { name } }")).isFalse();
        assertThat(isPureIntrospectionDocument("# __schema\n{ listAllFolder { id } }")).isFalse();
    }

    @Test
    void fragmentsAtTopLevelAndNonQueryOperationsAreRefused() {
        assertThat(isPureIntrospectionDocument("{ ... on Query { listAllFolder { id } } __typename }")).isFalse();
        assertThat(isPureIntrospectionDocument("{ ...F } fragment F on Query { listAllFolder { id } }")).isFalse();
        assertThat(isPureIntrospectionDocument("mutation { __typename }")).isFalse();
        // Two operations, one of them real data.
        assertThat(isPureIntrospectionDocument("query A { __schema { types { name } } } query B { listAllFolder { id } }")).isFalse();
    }

    @Test
    void malformedInputIsNotIntrospection() {
        assertThat(isPureIntrospectionDocument("")).isFalse();
        assertThat(isPureIntrospectionDocument(null)).isFalse();
        assertThat(isPureIntrospectionDocument("{ __schema {")).isFalse();
        assertThat(isPureIntrospectionRequest("not json")).isFalse();
        assertThat(isPureIntrospectionRequest("[{\"query\":\"{ __schema { types { name } } }\"}]")).isFalse();
        assertThat(isPureIntrospectionRequest("{\"query\": 42}")).isFalse();
        assertThat(isPureIntrospectionRequest("{\"operationName\":\"IntrospectionQuery\"}")).isFalse();
    }

    @Test
    void filterMarksOnlyPureIntrospection() {
        GraphQlIntrospectionFilter filter = new GraphQlIntrospectionFilter("/graphql/v1");

        ServerWebExchange introspection = post("{\"query\":\"{ __schema { types { name } } }\"}");
        AtomicReference<ServerWebExchange> seen = new AtomicReference<>();
        WebFilterChain chain = ex -> { seen.set(ex); return Mono.empty(); };
        filter.filter(introspection, chain).block();
        assertThat(introspection.<Boolean>getAttribute(GRAPHQL_INTROSPECTION_ATTRIBUTE)).isTrue();
        // The body is replayed downstream.
        String replayed = seen.get().getRequest().getBody()
                .map(b -> b.toString(StandardCharsets.UTF_8)).blockFirst();
        assertThat(replayed).contains("__schema");

        ServerWebExchange disguised = post("{\"query\":\"query IntrospectionQuery { listAllFolder { id } }\"}");
        filter.filter(disguised, ex -> Mono.empty()).block();
        assertThat(disguised.<Boolean>getAttribute(GRAPHQL_INTROSPECTION_ATTRIBUTE)).isNull();
    }

    @Test
    void oversizedUnauthenticatedBodyIsRejectedWith413() {
        GraphQlIntrospectionFilter filter = new GraphQlIntrospectionFilter("/graphql/v1");
        String huge = "{\"query\":\"" + "x".repeat(GraphQlIntrospectionFilter.MAX_UNAUTHENTICATED_BODY_BYTES + 10) + "\"}";
        ServerWebExchange exchange = post(huge);
        AtomicReference<Boolean> reached = new AtomicReference<>(false);
        filter.filter(exchange, ex -> { reached.set(true); return Mono.empty(); }).block();
        assertThat(reached.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(exchange.<Boolean>getAttribute(GRAPHQL_INTROSPECTION_ATTRIBUTE)).isNull();
    }

    private static ServerWebExchange post(String body) {
        MockServerHttpRequest request = MockServerHttpRequest.post("/graphql/v1")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
        return MockServerWebExchange.from(request);
    }
}
