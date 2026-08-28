package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.mcp.McpToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Pins the <b>default</b> MCP posture: read-only.
 * <p>
 * An MCP client is an autonomous agent acting on a document management system, so mutating
 * tools have to be switched on deliberately per deployment. These are the assertions that stop
 * that default from being weakened by accident.
 * <p>
 * Withholding the tools from {@code tools/list} is the primary mechanism — an agent cannot pick
 * a tool it never saw — but the refusal inside {@code tools/call} is asserted too, because a
 * client holding a cached tool list would otherwise slip past.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpReadOnlyModeIT extends AbstractMcpIT {

    public McpReadOnlyModeIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void useReadOnlyMode(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_ONLY");
    }

    @Test
    @DisplayName("mutating tools are withheld from tools/list")
    void mutatingToolsAreNotAdvertised() {
        assertThat(advertisedToolNames())
                .doesNotContainAnyElementsOf(McpToolCallbackProvider.MUTATING_TOOLS)
                .containsExactlyInAnyOrderElementsOf(McpToolCallbackProvider.READ_ONLY_TOOLS);
    }

    @Test
    @DisplayName("a mutating tool invoked anyway is refused, and nothing is created")
    void mutatingToolIsRefusedWhenCalledDirectly() {
        String folderName = "mcp-readonly-" + UUID.randomUUID().toString().substring(0, 8);

        // A client holding a cached tool list can still ask. The tool is not registered with the
        // MCP server at all in this mode, so the refusal comes back as a protocol-level failure
        // rather than as tool output — either shape is fine, as long as it is a refusal.
        JsonNode response = rpc(40, "tools/call", """
                {"name":"createFolder","arguments":{"name":"%s","parentFolderId":null}}"""
                .formatted(folderName));

        boolean rpcError = response.has("error");
        boolean toolError = response.path("result").path("isError").asBoolean(false);
        assertThat(rpcError || toolError)
                .as("createFolder must be refused in read-only mode; response was: %s", response)
                .isTrue();

        String found = callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName));
        assertThat(found)
                .as("a refused mutation must not have reached storage")
                .doesNotContain(folderName);
    }
}
