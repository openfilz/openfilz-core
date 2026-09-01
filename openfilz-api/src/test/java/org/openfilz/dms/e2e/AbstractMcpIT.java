package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared JSON-RPC plumbing for the MCP integration tests, so the read-write and read-only
 * suites exercise the endpoint through exactly the same client code.
 * <p>
 * Deliberately holds no {@code @Test} method: the two concrete suites assert opposite things
 * about the same endpoint, so inheriting each other's tests would be wrong.
 *
 * @see McpProtocolIT
 * @see McpReadOnlyModeIT
 */
public abstract class AbstractMcpIT extends TestContainersKeyCloakConfig {

    protected static final String MCP_ENDPOINT = "/mcp";

    /** Streamable HTTP requires the client to accept both shapes of response. */
    private static final String MCP_ACCEPT =
            MediaType.APPLICATION_JSON_VALUE + ", " + MediaType.TEXT_EVENT_STREAM_VALUE;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    protected String accessToken;

    protected AbstractMcpIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void registerMcpProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.mcp.active", () -> true);
        registry.add("spring.ai.vectorstore.pgvector.initialize-schema", () -> false);
        registry.add("spring.autoconfigure.exclude",
                () -> "org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration");
    }

    /**
     * Registers the {@code spring.ai.model.*} selectors. Deliberately <b>not</b> called from this
     * class's own {@code @DynamicPropertySource}: each concrete suite calls it, because a
     * subclass cannot override a value the superclass registers.
     * <p>
     * Spring builds the context customizer from the whole class hierarchy and the superclass
     * method registers last, so a subclass that "overrides" {@code spring.ai.model.chat} silently
     * loses and ends up testing the parent's configuration instead of its own — which is exactly
     * how a chat-model-specific regression test would quietly become vacuous. Passing the value
     * in makes the choice explicit at every call site and leaves no ordering to reason about.
     *
     * @param chat the chat-model selector — {@code "none"} for the suites that prove the MCP tool
     *             surface needs no OpenFilz-side LLM (the calling agent brings its own), or a real
     *             provider for {@link McpWithChatModelIT}, which pins the coexistence wiring.
     */
    protected static void registerModelSelectors(DynamicPropertyRegistry registry, String chat) {
        registry.add("spring.ai.model.chat", () -> chat);
        registry.add("spring.ai.model.embedding", () -> "none");
        registry.add("spring.ai.model.image", () -> "none");
        registry.add("spring.ai.model.moderation", () -> "none");
        registry.add("spring.ai.model.audio.speech", () -> "none");
        registry.add("spring.ai.model.audio.transcription", () -> "none");
    }

    @BeforeEach
    void authenticate() {
        accessToken = getAccessToken("admin-user");
    }

    // ---------------------------------------------------------------- helpers

    protected List<String> advertisedToolNames() {
        return listTools().stream().map(tool -> tool.get("name").asString()).toList();
    }

    protected List<JsonNode> listTools() {
        JsonNode tools = expectResult(rpc(2, "tools/list", "{}")).path("tools");
        List<JsonNode> result = new ArrayList<>();
        tools.forEach(result::add);
        return result;
    }

    /** Invoke a tool and return the concatenated text of its content blocks. */
    protected String callToolText(String toolName, String argumentsJson) {
        JsonNode result = expectResult(rpc(3, "tools/call", """
                {"name":"%s","arguments":%s}""".formatted(toolName, argumentsJson)));

        StringBuilder text = new StringBuilder();
        result.path("content").forEach(block -> text.append(block.path("text").asString("")));
        return text.toString();
    }

    /** resources/read for a URI, returning the raw JSON-RPC response (result or error). */
    protected JsonNode readResource(String uri) {
        return rpc(7, "resources/read", """
                {"uri":"%s"}""".formatted(uri));
    }

    protected JsonNode expectResult(JsonNode response) {
        assertThat(response.has("error"))
                .as("JSON-RPC call failed: %s", response)
                .isFalse();
        return response.get("result");
    }

    /** POST a JSON-RPC request with the caller's bearer token and parse the response. */
    protected JsonNode rpc(int id, String method, String paramsJson) {
        byte[] body = webTestClient.post().uri(MCP_ENDPOINT)
                .header(HttpHeaders.ACCEPT, MCP_ACCEPT)
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .bodyValue(rpcBody(id, method, paramsJson))
                .exchange()
                .expectStatus().is2xxSuccessful()
                .expectBody().returnResult().getResponseBody();

        assertThat(body).as("empty response to %s", method).isNotNull();
        return JSON.readTree(unwrapServerSentEvent(new String(body, StandardCharsets.UTF_8)));
    }

    protected static String rpcBody(int id, String method, String paramsJson) {
        return paramsJson == null
                ? """
                  {"jsonrpc":"2.0","id":%d,"method":"%s"}""".formatted(id, method)
                : """
                  {"jsonrpc":"2.0","id":%d,"method":"%s","params":%s}""".formatted(id, method, paramsJson);
    }

    /**
     * Streamable HTTP may answer either as plain JSON or as a one-event SSE stream; both are
     * spec-valid, and which one comes back is the transport's choice. Accept both so the suite
     * pins OpenFilz's behaviour rather than the SDK's framing decision.
     */
    private static String unwrapServerSentEvent(String body) {
        if (!body.startsWith("event:") && !body.startsWith("data:")) {
            return body;
        }
        return body.lines()
                .filter(line -> line.startsWith("data:"))
                .map(line -> line.substring("data:".length()).trim())
                .reduce("", String::concat);
    }
}
