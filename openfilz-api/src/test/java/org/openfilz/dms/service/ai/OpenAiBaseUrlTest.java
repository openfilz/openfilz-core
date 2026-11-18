package org.openfilz.dms.service.ai;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.AiProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

/**
 * The address and the key of a programmatically built OpenAI model. Handed {@code null}, the OpenAI
 * SDK reads the {@code OPENAI_BASE_URL} and {@code OPENAI_API_KEY} environment variables, i.e. the
 * deployment's own endpoint and key. Deployments write that address without its version segment
 * ({@code https://api.openai.com}), and the SDK appends {@code /chat/completions} to the base URL
 * as it is (pinned below against a stand-in server): every call went to
 * {@code https://api.openai.com/chat/completions}, which OpenAI answers with 404, a valid key
 * included. So nothing is left to the environment: a personal OPENAI model always gets the
 * platform's address with {@code /v1}, a model of the deployment's own configuration gets the
 * deployment's endpoint in the SDK's form, and a missing key stays missing.
 */
class OpenAiBaseUrlTest {

    private HttpServer server;
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> credentials = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            paths.add(exchange.getRequestURI().getPath());
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            credentials.add(authorization == null ? "" : authorization);
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

    /** A resolver that builds real clients; {@code deploymentBaseUrl} is its {@code spring.ai.openai.base-url}. */
    private static UserChatClientResolver resolver(String deploymentBaseUrl) {
        UserChatClientResolver resolver = mock(UserChatClientResolver.class, CALLS_REAL_METHODS);
        ReflectionTestUtils.setField(resolver, "toolCallingManager",
                ToolCallingManager.builder().build());
        MockEnvironment environment = new MockEnvironment();
        if (deploymentBaseUrl != null) {
            environment.setProperty("spring.ai.openai.base-url", deploymentBaseUrl);
        }
        ReflectionTestUtils.setField(resolver, "environment", environment);
        return resolver;
    }

    private static ChatModel build(AiProvider provider, String baseUrl) {
        return resolver(null).buildChatModel(provider, "sk-test", baseUrl, "m");
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
    @DisplayName("OPENAI_COMPATIBLE talks to the base URL its owner saved, and is refused without one")
    void compatibleKeepsItsBaseUrl() {
        assertThat(UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI_COMPATIBLE, base() + "/v1"))
                .isEqualTo(base() + "/v1");
        // null would be the SDK's cue to read OPENAI_BASE_URL: the deployment's endpoint, with a personal key
        assertThatThrownBy(() -> UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI_COMPATIBLE, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> UserChatClientResolver.openAiBaseUrl(AiProvider.OPENAI_COMPATIBLE, " "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("the SDK appends /chat/completions to the base URL as given: the default must carry /v1")
    void theSdkAppendsTheEndpointToTheBaseUrlAsGiven() {
        assertThat(build(AiProvider.OPENAI_COMPATIBLE, base() + "/v1").call("hi")).isEqualTo("OK");
        assertThat(build(AiProvider.OPENAI_COMPATIBLE, base()).call("hi")).isEqualTo("OK");
        assertThat(paths).containsExactly("/v1/chat/completions", "/chat/completions");
    }

    @Test
    @DisplayName("the deployment's endpoint: unset or the bare platform host is the platform with /v1, another server is used as written")
    void deploymentEndpointInTheSdksForm() {
        assertThat(OpenAiBaseUrls.deployment(null)).isEqualTo(OpenAiBaseUrls.PLATFORM);
        assertThat(OpenAiBaseUrls.deployment("  ")).isEqualTo(OpenAiBaseUrls.PLATFORM);
        // the form the compose files, the chart and the documentation gave for years
        assertThat(OpenAiBaseUrls.deployment("https://api.openai.com")).isEqualTo("https://api.openai.com/v1");
        assertThat(OpenAiBaseUrls.deployment("https://API.openai.com/ ")).isEqualTo("https://api.openai.com/v1");
        assertThat(OpenAiBaseUrls.deployment("https://api.openai.com/v1")).isEqualTo("https://api.openai.com/v1");
        // not ours to complete: an API root may or may not carry a version segment
        assertThat(OpenAiBaseUrls.deployment("http://vllm:8000")).isEqualTo("http://vllm:8000");
        assertThat(OpenAiBaseUrls.deployment(" https://api.mistral.ai/v1 ")).isEqualTo("https://api.mistral.ai/v1");
        assertThat(OpenAiBaseUrls.deployment("https://api.openai.com.example.test")).isEqualTo("https://api.openai.com.example.test");
    }

    @Test
    @DisplayName("the gateway's address is its root: the version segment of /v1/chat/completions is added once")
    void gatewayEndpointInTheSdksForm() {
        assertThat(OpenAiBaseUrls.gateway("https://ai.openfilz.com")).isEqualTo("https://ai.openfilz.com/v1");
        assertThat(OpenAiBaseUrls.gateway("https://ai.openfilz.com/")).isEqualTo("https://ai.openfilz.com/v1");
        assertThat(OpenAiBaseUrls.gateway("https://ai.openfilz.com/v1")).isEqualTo("https://ai.openfilz.com/v1");
    }

    @Test
    @DisplayName("a deployment's openai model calls the deployment's own endpoint with the server key, not the OpenAI platform")
    void aDeploymentOpenAiModelTalksToTheDeploymentsEndpoint() {
        UserChatClientResolver resolver = resolver(base() + "/v1");

        assertThat(resolver.buildDeploymentChatModel(AiProvider.OPENAI, "sk-server", "m").call("hi")).isEqualTo("OK");
        assertThat(resolver.buildDeploymentChatModel(AiProvider.OPENAI_COMPATIBLE, "sk-server", "m").call("hi")).isEqualTo("OK");

        assertThat(paths).containsExactly("/v1/chat/completions", "/v1/chat/completions");
        assertThat(credentials).containsExactly("Bearer sk-server", "Bearer sk-server");
        assertThat(UserChatClientResolver.deploymentOpenAiBaseUrl(new MockEnvironment()))
                .as("no address configured: the platform, never the SDK's own reading of the environment")
                .isEqualTo(OpenAiBaseUrls.PLATFORM);
    }

    @Test
    @DisplayName("a model without a key is built without one: the SDK is never left to read the deployment's OPENAI_API_KEY")
    void aMissingKeyIsNeverLeftToTheEnvironment() {
        assertThat(UserChatClientResolver.openAiApiKey(null)).isEmpty();
        assertThat(UserChatClientResolver.openAiApiKey("sk-user")).isEqualTo("sk-user");

        ChatModel keyless = resolver(null).buildChatModel(AiProvider.OPENAI_COMPATIBLE, null, base() + "/v1", "m");

        assertThat(keyless.call("hi")).isEqualTo("OK");
        // The SDK's client for "no credential": nothing that could be a real key reaches the server.
        assertThat(credentials).singleElement().satisfies(sent -> assertThat(sent).doesNotContain("sk-"));
    }
}
