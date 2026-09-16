package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
 * WORM has to hold on {@code /mcp} as well, and this suite is the one that would have caught it
 * not holding.
 * <p>
 * A tool call never passes through the HTTP security chain — it runs in-process on a tool thread
 * with no request to match — so {@code AbstractSecurityService.authorize}, where the WORM
 * prohibition lives, is simply not consulted. Before the perimeter was re-applied in
 * {@code DefaultAiToolRolePolicy}, a deployment could advertise a write-once repository over REST
 * and let an agent delete a document through {@code tools/call} with the same token. That is the
 * shape of hole {@code ToolCapability} was introduced for in the first place, one axis over.
 * <p>
 * Note the mode is deliberately {@code READ_WRITE} here: setting it to {@code READ_ONLY} would
 * make the refusals pass for the wrong reason and prove nothing about WORM.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpWormModeIT extends AbstractMcpIT {

    public McpWormModeIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void useWormMode(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        // READ_WRITE on purpose: the refusals below must come from WORM, not from the MCP posture.
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.security.worm-mode", () -> Boolean.TRUE);
        registry.add("openfilz.calculate-checksum", () -> Boolean.TRUE);
    }

    @Test
    @DisplayName("a mutating tool is refused under WORM, and nothing is created")
    void mutatingToolIsRefusedUnderWorm() {
        String folderName = "mcp-worm-" + UUID.randomUUID().toString().substring(0, 8);

        assertThat(isRefused(callTool("createFolder", """
                {"name":"%s","parentFolderId":null}""".formatted(folderName))))
                .as("createFolder must be refused while the repository is write-once")
                .isTrue();

        assertThat(callToolText("queryDocuments", """
                {"folder":"all","nameLike":"%s","type":"FOLDER","pageSize":10}""".formatted(folderName)))
                .as("a refused mutation must not have reached storage")
                .doesNotContain(folderName);
    }

    @Test
    @DisplayName("deletion in particular is refused — the promise WORM exists to make")
    void deletionIsRefusedUnderWorm() {
        assertThat(isRefused(callTool("deleteDocument", """
                {"documentId":"%s"}""".formatted(UUID.randomUUID()))))
                .as("deleteDocument must be refused under WORM")
                .isTrue();
    }

    @Test
    @DisplayName("reads are untouched — a write-once archive is still a readable one")
    void readingToolsStillWorkUnderWorm() {
        // whoami needs no role at all; queryDocuments needs READER/CONTRIBUTOR. Both are reads, so
        // WORM must leave them alone — a perimeter that also blinded the agent would be useless.
        assertThat(callToolText("whoami", "{}")).isNotBlank();
        assertThat(isRefused(callTool("queryDocuments", """
                {"folder":"all","pageSize":5}""")))
                .as("queryDocuments is a read and must stay available under WORM")
                .isFalse();
    }

    private JsonNode callTool(String toolName, String argumentsJson) {
        return rpc(41, "tools/call", """
                {"name":"%s","arguments":%s}""".formatted(toolName, argumentsJson));
    }

    /** A refusal may surface as a JSON-RPC error or as {@code isError} tool output; both count. */
    private static boolean isRefused(JsonNode response) {
        return response.has("error") || response.path("result").path("isError").asBoolean(false);
    }
}
