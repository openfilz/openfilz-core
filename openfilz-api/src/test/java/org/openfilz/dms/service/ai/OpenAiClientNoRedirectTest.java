package org.openfilz.dms.service.ai;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The programmatically built OpenAI / OpenAI-compatible clients never follow a redirect
 * ({@link UserChatClientResolver#NO_REDIRECTS}): a base URL is checked where it is saved, and a server
 * answering {@code 3xx Location: <another address>} must not lead the client to an address nobody
 * checked. A client built without the customizers follows it, which is what the first test pins: the
 * day Spring AI's client stops following redirects by itself, the workaround can go.
 */
class OpenAiClientNoRedirectTest {

    private static final MediaType JSON = MediaType.get("application/json");

    private HttpServer server;
    private final AtomicInteger targetHits = new AtomicInteger();
    private final AtomicInteger redirectHits = new AtomicInteger();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/found/v1/chat/completions", exchange -> redirect(exchange, 302));
        server.createContext("/temporary/v1/chat/completions", exchange -> redirect(exchange, 307));
        server.createContext("/target/v1/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            targetHits.incrementAndGet();
            byte[] body = "{\"internal\":\"secret\"}".getBytes(StandardCharsets.UTF_8);
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

    private void redirect(HttpExchange exchange, int status) throws IOException {
        exchange.getRequestBody().readAllBytes();
        redirectHits.incrementAndGet();
        exchange.getResponseHeaders().add("Location", base() + "/target/v1/chat/completions");
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Request post(String url) {
        return new Request.Builder().url(url).post(RequestBody.create("{\"model\":\"m\"}", JSON)).build();
    }

    @Test
    @DisplayName("control: Spring AI's client alone follows the redirect to the other address")
    void withoutTheCustomizersTheRedirectIsFollowed() throws IOException {
        SpringAiOpenAiHttpClient client = SpringAiOpenAiHttpClient.builder().build();
        try (Response response = client.getOkHttpClient()
                .newCall(post(base() + "/temporary/v1/chat/completions")).execute()) {
            assertThat(response.code()).isEqualTo(200);
        } finally {
            client.close();
        }
        assertThat(targetHits.get()).as("redirect target reached").isEqualTo(1);
    }

    @Test
    @DisplayName("with NO_REDIRECTS a 302 or a 307 comes back as it is: the other address is never called")
    void withTheCustomizersTheRedirectIsNotFollowed() throws IOException {
        SpringAiOpenAiHttpClient.Builder builder = SpringAiOpenAiHttpClient.builder();
        UserChatClientResolver.NO_REDIRECTS.forEach(customizer -> customizer.customize(builder));
        SpringAiOpenAiHttpClient client = builder.build();
        OkHttpClient http = client.getOkHttpClient();
        try {
            try (Response found = http.newCall(post(base() + "/found/v1/chat/completions")).execute()) {
                assertThat(found.code()).isEqualTo(302);
                assertThat(found.body().string()).doesNotContain("secret");
            }
            try (Response temporary = http.newCall(post(base() + "/temporary/v1/chat/completions")).execute()) {
                assertThat(temporary.code()).isEqualTo(307);
            }
        } finally {
            client.close();
        }
        assertThat(redirectHits.get()).isEqualTo(2);
        assertThat(targetHits.get()).as("redirect target reached").isZero();
    }
}
