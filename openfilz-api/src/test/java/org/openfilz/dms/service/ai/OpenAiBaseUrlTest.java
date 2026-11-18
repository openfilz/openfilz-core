package org.openfilz.dms.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * The base URL of a programmatically built OpenAI model. Handed {@code null}, the OpenAI SDK reads
 * the {@code OPENAI_BASE_URL} environment variable, which deployments set for Spring AI in another form
 * ({@code https://api.openai.com}, no {@code /v1}); the SDK then posted to
 * {@code https://api.openai.com/chat/completions} and OpenAI answered 404, a valid key included. So the
 * OPENAI provider always gets an explicit address, with the version segment, since the SDK appends
 * {@code /chat/completions} to the base URL as it is (pinned below against a stand-in server).
 */
class OpenAiBaseUrlTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            paths.add(exchange.getRequestURI().getPath());
            byte[] body = ("{\"id\":\"x\",\"object\":\"chat.completion\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},"
                    + "\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,"
                    + "\"total_tokens\":2}}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static ChatModel build(AiProvider provider, String baseUrl) {
        UserChatClientResolver resolver = mock(UserChatClientResolver.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(resolver, "toolCallingManager",
                ToolCallingManager.builder().build());
        return resolver.buildChatModel(provider, "sk-test", baseUrl, "m");
    }

    @Test
    @DisplayName("OPENAI always gets the platform's address with /v1, whatever the deployment's environment says")
    void openAiNeverLeavesTheBaseUrlToTheEnvironment() {
        assertThat(UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI, null))
                .isEqualTo("https://api.openai.com/v1");
        // a base URL stored with an OPENAI row (none should be) does not redirect it either
        assertThat(UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI, base()))
                .isEqualTo(UserChatClientResolver.OPENAI_PLATFORM_BASE_URL);
    }

    @Test
    @DisplayName("OPENAI_COMPATIBLE talks to the base URL its owner saved")
    void compatibleKeepsItsBaseUrl() {
        assertThat(UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI_COMPATIBLE, base() + "/v1"))
                .isEqualTo(base() + "/v1");
    }

    @Test
    @DisplayName("the SDK appends /chat/completions to the base URL as given: the default must carry /v1")
    void theSdkAppendsTheEndpointToTheBaseUrlAsGiven() {
        assertThat(build(AiProvider.OPENAI_COMPATIBLE, base() + "/v1").call("hi")).isEqualTo("OK");
        assertThat(build(AiProvider.OPENAI_COMPATIBLE, base()).call("hi")).isEqualTo("OK");
        assertThat(paths).containsExactly("/v1/chat/completions", "/chat/completions");
    }
}
