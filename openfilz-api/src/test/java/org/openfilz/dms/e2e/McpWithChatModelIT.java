package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.mcp.McpToolCallbackProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Regression guard: the MCP server must coexist with a real {@link ChatModel} bean.
 * <p>
 * <b>Why this is a separate suite.</b> The other MCP suites pin every {@code spring.ai.model.*}
 * selector to {@code none}, which is the right default there — it proves the tool surface stands
 * up with no LLM of OpenFilz's own. But it also means no {@code ChatModel} bean is ever created,
 * and that is precisely the configuration in which the following bug is invisible:
 * <pre>
 * toolCallbackResolver → McpToolCallbackProvider.getToolCallbacks()
 *                      → ollamaChatModel → toolCallingManager → toolCallbackResolver
 * </pre>
 * Spring AI invokes {@code getToolCallbacks()} from beans that are themselves still being
 * created, so a ChatModel resolved inside that call closes the loop and the context refuses to
 * start with "dependencies of some of the beans form a cycle".
 * <p>
 * It was found by {@code McpNativeE2EIT} — the EE native image bakes its ChatModel bean
 * definition in at AOT time, so it always has one regardless of {@code openfilz.ai.active} — but
 * it is not a native-image defect: any deployment that runs the AI assistant and the MCP server
 * together hits it. This suite moves that failure from a ~20-minute native build back to a
 * ~30-second JVM boot.
 *
 * @see McpToolCallbackProvider
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class McpWithChatModelIT extends AbstractMcpIT {

    @Autowired
    private ApplicationContext applicationContext;

    public McpWithChatModelIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    /**
     * Ask for a real chat model. Ollama is chosen because its auto-configuration builds a
     * {@code ChatModel} bean without contacting anything (model pulling defaults to
     * {@code NEVER}), so no Ollama server is needed — this suite is about bean wiring, not
     * inference.
     */
    @DynamicPropertySource
    static void enableAChatModel(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "ollama");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
    }

    @Test
    @DisplayName("a ChatModel bean really is present — otherwise this suite proves nothing")
    void chatModelBeanIsActuallyPresent() {
        // Guards the guard. If `spring.ai.model.chat` ever resolves to "none" here — a stray
        // registration elsewhere in the hierarchy, a changed Spring AI selector name — the context
        // would contain no ChatModel, the cycle could not form, and the assertions below would
        // pass while testing nothing at all. This assertion is what caught exactly that.
        assertThat(applicationContext.getBeanNamesForType(ChatModel.class))
                .as("""
                        no ChatModel bean in the context — `spring.ai.model.chat=ollama` did not \
                        take effect, so this regression test is vacuous. Do not delete it: make \
                        the selector work.""")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the MCP tool surface still comes up when a ChatModel is configured")
    void mcpServesItsToolsAlongsideAChatModel() {
        // The context starting at all is most of the assertion: the cycle aborts the refresh.
        Set<String> expected = new HashSet<>(McpToolCallbackProvider.READ_ONLY_TOOLS);
        expected.addAll(McpToolCallbackProvider.MUTATING_TOOLS);

        assertThat(advertisedToolNames()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("a tool still executes when a ChatModel is configured")
    void toolStillExecutes() {
        String found = callToolText("queryDocuments", """
                {"sortBy":"updatedAt","sortOrder":"DESC","pageSize":5,"countOnly":false}""");

        assertThat(found).as("queryDocuments returned nothing at all").isNotEmpty();
    }
}
