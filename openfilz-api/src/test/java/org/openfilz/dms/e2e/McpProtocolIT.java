package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.mcp.McpToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Layer 1 of the MCP test strategy: protocol conformance over the real HTTP stack, with a real
 * Keycloak bearer token, against the JVM build (see
 * {@code openfilz-enterprise/docs/private/mcp-server-plan.md}).
 * <p>
 * Two jobs, and the second is why this suite is exhaustive rather than representative:
 * <ol>
 *   <li>Pin the wire contract an external agent sees — handshake, advertised tool surface,
 *       schemas, error shape, and the fact that an unauthenticated call gets nowhere.</li>
 *   <li><b>Drive every tool at least once.</b> Layer 2 runs this suite under GraalVM's tracing
 *       agent to derive the native-image reflection metadata; a tool no test calls is a tool
 *       whose reflective path never gets registered, and it then fails only in an EE native
 *       deployment. {@link #everyAdvertisedToolIsCallable()} exists for that reason, not for its
 *       assertions.</li>
 * </ol>
 * Runs in {@code READ_WRITE} so the full surface is reachable; {@link McpReadOnlyModeIT} pins
 * the default read-only posture.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpProtocolIT extends AbstractMcpIT {

    public McpProtocolIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void useReadWriteMode(DynamicPropertyRegistry registry) {
        // "none": this suite also proves the tool surface stands up with no ChatModel bean at
        // all. McpWithChatModelIT covers the opposite case.
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
    }

    // ---------------------------------------------------------------- handshake

    @Test
    @DisplayName("initialize returns the server identity, protocol version and instructions")
    void initializeHandshake() {
        JsonNode result = expectResult(rpc(1, "initialize", """
                {"protocolVersion":"2025-06-18",
                 "capabilities":{},
                 "clientInfo":{"name":"openfilz-protocol-it","version":"1.0.0"}}"""));

        assertThat(result.path("protocolVersion").asString())
                .as("server must negotiate a protocol version").isNotBlank();
        assertThat(result.path("serverInfo").path("name").asString()).isEqualTo("openfilz");
        assertThat(result.path("capabilities").has("tools"))
                .as("a tools-only MCP server must advertise the tools capability").isTrue();
        assertThat(result.path("instructions").asString())
                .as("instructions tell the calling agent how to use the tool surface")
                .contains("queryDocuments");
    }

    // ---------------------------------------------------------------- tools/list

    @Test
    @DisplayName("tools/list advertises the whole DocumentAiTools surface in read-write mode")
    void toolsListAdvertisesEveryTool() {
        Set<String> expected = new HashSet<>(McpToolCallbackProvider.READ_ONLY_TOOLS);
        expected.addAll(McpToolCallbackProvider.MUTATING_TOOLS);

        assertThat(advertisedToolNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("every advertised tool carries a description and a JSON input schema")
    void everyToolIsSelfDescribing() {
        for (JsonNode tool : listTools()) {
            String name = tool.get("name").asString();
            assertThat(tool.path("description").asString())
                    .as("%s must describe itself — the description is what an agent selects on", name)
                    .isNotBlank();
            assertThat(tool.path("inputSchema").path("type").asString())
                    .as("%s must expose a JSON Schema for its arguments", name)
                    .isEqualTo("object");
        }
    }

    @Test
    @DisplayName("queryDocuments exposes its documented parameters in the input schema")
    void queryDocumentsSchemaIsComplete() {
        JsonNode properties = listTools().stream()
                .filter(tool -> "queryDocuments".equals(tool.get("name").asString()))
                .findFirst()
                .orElseThrow()
                .path("inputSchema").path("properties");

        assertThat(properties.propertyNames())
                .contains("folder", "nameLike", "type", "sortBy", "sortOrder", "pageSize", "countOnly");
    }

    // ---------------------------------------------------------------- security

    @Test
    @DisplayName("an unauthenticated MCP request is rejected before reaching any tool")
    void unauthenticatedRequestIsRejected() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(rpcBody(99, "tools/list", null))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("a bearer token that is not a valid JWT is rejected")
    void garbageTokenIsRejected() {
        webTestClient.post().uri(MCP_ENDPOINT)
                .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .header(org.springframework.http.HttpHeaders.AUTHORIZATION, "Bearer not-a-jwt")
                .bodyValue(rpcBody(98, "tools/list", null))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------------------------------------------------------------- tools/call

    @Test
    @DisplayName("createFolder then queryDocuments round-trips through the MCP tool layer")
    void toolCallCreatesAndFindsAFolder() {
        String folderName = "mcp-it-" + UUID.randomUUID().toString().substring(0, 8);

        String created = callToolText("createFolder", """
                {"name":"%s"}""".formatted(folderName));
        assertThat(created)
                .as("createFolder should report what it created")
                .containsIgnoringCase(folderName);

        String found = callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName));
        assertThat(found)
                .as("a folder created through MCP must be findable through MCP")
                .contains(folderName);
    }

    @Test
    @DisplayName("describeImage degrades explicitly when no chat model is configured")
    void visionToolDegradesWithoutAChatModel() {
        // The context deliberately has no ChatModel bean (selectors pinned to "none"): an MCP
        // deployment need not run an LLM of its own. The tool must say so rather than NPE.
        String answer = callToolText("describeImage", """
                {"imageName":"whatever.png","task":"describe"}""");

        assertThat(answer).containsIgnoringCase("unavailable");
    }

    @Test
    @DisplayName("an unknown tool name fails cleanly instead of surfacing a stack trace")
    void unknownToolIsRejectedCleanly() {
        JsonNode response = rpc(50, "tools/call", """
                {"name":"deleteEverything","arguments":{}}""");

        boolean rpcError = response.has("error");
        boolean toolError = response.path("result").path("isError").asBoolean(false);
        assertThat(rpcError || toolError)
                .as("calling a tool that does not exist must fail; response was: %s", response)
                .isTrue();
    }

    /**
     * Calls every tool the server advertises. Assertions are deliberately weak — the arguments
     * are benign and several tools legitimately answer "not found". What matters is that each
     * tool completes a full dispatch, so layer 2's tracing agent observes every reflective path.
     */
    @Test
    @DisplayName("every advertised tool completes a dispatch (native-hint trace driver)")
    void everyAdvertisedToolIsCallable() {
        String probe = "mcp-probe-" + UUID.randomUUID().toString().substring(0, 8);

        for (String tool : advertisedToolNames()) {
            String arguments = argumentsFor(tool, probe);

            JsonNode response = rpc(100, "tools/call", """
                    {"name":"%s","arguments":%s}""".formatted(tool, arguments));

            assertThat(response.has("error"))
                    .as("tool '%s' must not fail at the protocol level; response was: %s", tool, response)
                    .isFalse();
            assertThat(response.path("result").path("content").isArray())
                    .as("tool '%s' must return MCP content blocks", tool)
                    .isTrue();
            // Not merely "no exception": argument-schema validation fails BEFORE the tool body
            // runs, so an isError result would trace no reflective path at all and layer 2 would
            // silently under-generate the native metadata.
            assertThat(response.path("result").path("isError").asBoolean(false))
                    .as("tool '%s' must actually execute, not fail validation; response was: %s", tool, response)
                    .isFalse();
        }
    }

    private static String argumentsFor(String tool, String probe) {
        return switch (tool) {
            case "queryDocuments" -> """
                    {"sortBy":"updatedAt","sortOrder":"DESC","pageSize":5,"countOnly":false}""";
            case "readDocumentContent" -> """
                    {"documentName":"%s"}""".formatted(probe);
            case "getDocumentPath" -> """
                    {"documentId":"%s"}""".formatted(UUID.randomUUID());
            case "describeImage" -> """
                    {"imageName":"%s","task":"describe"}""".formatted(probe);
            case "createFolder" -> """
                    {"name":"%s"}""".formatted(probe);
            case "writeFile" -> """
                    {"fileName":"%s.txt","content":"hello from MCP"}""".formatted(probe);
            case "moveDocuments" -> """
                    {"documentNames":"%s.txt","targetFolder":"%s"}""".formatted(probe, probe);
            case "renameDocument" -> """
                    {"documentName":"%s.txt","newName":"%s-renamed.txt"}""".formatted(probe, probe);
            default -> throw new AssertionError("""
                    Unknown MCP tool '%s'. A tool was added to DocumentAiTools without being \
                    classified here — add arguments for it (and list it in \
                    McpToolCallbackProvider.MUTATING_TOOLS if it changes anything), otherwise \
                    layer 2 never traces it and it fails in the EE native image."""
                    .formatted(tool));
        };
    }
}
