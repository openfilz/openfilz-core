package org.openfilz.dms.service.ai;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.AiProvider;
import org.openfilz.dms.repository.UserAiSettingsRepository;
import org.openfilz.dms.service.ai.AiFailoverPolicy.Failure;
import org.openfilz.dms.service.impl.AiSettingsCipher;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.mock;

/**
 * A spent Gemini key must reach {@link AiFallbackChain} at once, not after the Google GenAI
 * SDK's own retry cycle. The SDK retries a 429 five times with 1 s, 2 s, 4 s, 8 s backoff plus
 * jitter by default — measured at 5 requests in 18.7 s against this very mock, 30 to 50 s against
 * the real API — so on the demo every insight and filing stalled that long on each exhausted
 * model of the chain before failing over. The programmatically built client turns the SDK retry
 * off: one request, one failure, straight to the next candidate.
 */
class UserChatClientResolverGoogleRetryTest {

    private static final String QUOTA_BODY = """
            {"error":{"code":429,"message":"You exceeded your current quota, please check your plan and billing details.","status":"RESOURCE_EXHAUSTED"}}
            """;

    private final MockWebServer gemini = new MockWebServer();

    @BeforeEach
    void startExhaustedGemini() throws IOException {
        gemini.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return new MockResponse()
                        .setResponseCode(429)
                        .addHeader("Content-Type", "application/json")
                        .setBody(QUOTA_BODY);
            }
        });
        gemini.start();
    }

    @AfterEach
    void stop() throws IOException {
        gemini.shutdown();
    }

    private ChatModel geminiModelAgainstMock() {
        UserChatClientResolver resolver = new UserChatClientResolver(mock(ChatModel.class),
                mock(ToolCallingManager.class), mock(UserAiSettingsRepository.class),
                mock(AiSettingsCipher.class), new MockEnvironment(), false);
        return resolver.buildChatModel(AiProvider.GOOGLE, "spent-key", gemini.url("/").toString(), "gemini-test");
    }

    @Test
    @DisplayName("a 429 surfaces after a single request, in well under the SDK's default retry cycle")
    void quotaErrorIsNotRetriedBySdk() {
        ChatModel model = geminiModelAgainstMock();

        long started = System.nanoTime();
        Throwable failure = catchThrowable(() -> model.call(new Prompt("hello")));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure).as("the 429 must propagate to the caller").isNotNull();
        assertThat(gemini.getRequestCount()).as("one HTTP attempt, no SDK retry").isEqualTo(1);
        // The SDK's default cycle sleeps 1 + 2 + 4 + 8 s (plus jitter) before the fifth attempt
        // fails: 15 s at the least. A single attempt is bounded here by client warm-up only.
        assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("the surfaced failure is what the fallback chain benches as QUOTA_EXHAUSTED")
    void quotaErrorIsClassifiedForFailover() {
        ChatModel model = geminiModelAgainstMock();

        Throwable failure = catchThrowable(() -> model.call(new Prompt("hello")));

        assertThat(failure).isNotNull();
        assertThat(AiFailoverPolicy.classify(failure)).isEqualTo(Failure.QUOTA_EXHAUSTED);
    }

    @Test
    @DisplayName("without a base URL the client still builds against the Gemini API with retry off")
    void defaultClientBuilds() {
        assertThat(UserChatClientResolver.googleClient("key", null)).isNotNull();
        assertThat(UserChatClientResolver.GOOGLE_NO_SDK_RETRY.attempts()).contains(1);
    }
}
