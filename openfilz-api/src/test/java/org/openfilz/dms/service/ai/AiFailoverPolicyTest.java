package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.ai.AiFailoverPolicy.Failure;

import java.net.ConnectException;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Failure classification is the load-bearing half of chat-model failover: misread a broken API
 * key as an exhausted quota and OpenFilz silently answers from the wrong model instead of telling
 * the operator their key is wrong.
 * <p>
 * The provider SDK exceptions are not on the test classpath as constructible fixtures, and their
 * real shape is what matters, so each case reproduces the message text and wrapping that the
 * provider actually produces — most importantly the verbatim Google GenAI 404 that took a
 * production deployment down when {@code gemini-2.5-flash} was retired.
 */
class AiFailoverPolicyTest {

    /** Stand-in for {@code com.google.genai.errors.ClientException} (message shape is what we match on). */
    private static class ClientException extends RuntimeException {
        ClientException(String message) { super(message); }
    }

    /** Stand-in for the Anthropic/OpenAI SDKs' typed rate-limit exception (matched on type name). */
    private static class RateLimitException extends RuntimeException {
        RateLimitException(String message) { super(message); }
    }

    private static class AuthenticationException extends RuntimeException {
        AuthenticationException(String message) { super(message); }
    }

    /** Spring AI wraps every provider failure in this generic runtime exception. */
    private static RuntimeException springAiWrapped(Throwable cause) {
        return new RuntimeException("Failed to generate content", cause);
    }

    @Test
    @DisplayName("the retired-model 404 that broke production is a failover, not a hard error")
    void classifiesRetiredGoogleModel() {
        RuntimeException error = springAiWrapped(new ClientException(
                "404 . This model models/gemini-2.5-flash is no longer available to new users. "
                        + "Please update your code to use models/gemini-3.6-flash for the latest features and improvements."));

        assertThat(AiFailoverPolicy.classify(error)).isEqualTo(Failure.MODEL_UNAVAILABLE);
        assertThat(AiFailoverPolicy.classify(error).shouldFailover()).isTrue();
    }

    @Test
    @DisplayName("exhausted quota is detected from status, wording and typed exceptions alike")
    void classifiesQuotaExhaustion() {
        assertThat(AiFailoverPolicy.classify(springAiWrapped(new ClientException(
                "429 . RESOURCE_EXHAUSTED. You exceeded your current quota, please check your plan and billing details."))))
                .isEqualTo(Failure.QUOTA_EXHAUSTED);

        assertThat(AiFailoverPolicy.classify(new ClientException(
                "429 . Quota exceeded for quota metric 'Generate Content API requests per day'")))
                .isEqualTo(Failure.QUOTA_EXHAUSTED);

        assertThat(AiFailoverPolicy.classify(springAiWrapped(new RateLimitException("rate limited"))))
                .isEqualTo(Failure.QUOTA_EXHAUSTED);

        assertThat(AiFailoverPolicy.classify(new RuntimeException("status code: 429, insufficient_quota")))
                .isEqualTo(Failure.QUOTA_EXHAUSTED);
    }

    @Test
    @DisplayName("some providers report a spent quota as 403 — the wording decides")
    void classifiesQuotaReportedAsForbidden() {
        assertThat(AiFailoverPolicy.classify(new ClientException("403 . Quota exceeded for this project")))
                .isEqualTo(Failure.QUOTA_EXHAUSTED);
        assertThat(AiFailoverPolicy.classify(new ClientException("403 . PERMISSION_DENIED: caller lacks permission")))
                .isEqualTo(Failure.NOT_FAILOVER);
    }

    @Test
    @DisplayName("provider outages and unreachable hosts are worth another model")
    void classifiesTransientProviderFailures() {
        assertThat(AiFailoverPolicy.classify(new ClientException("503 . The model is overloaded. Please try again later.")))
                .isEqualTo(Failure.PROVIDER_OVERLOADED);
        assertThat(AiFailoverPolicy.classify(new ClientException("500 . Internal error encountered.")))
                .isEqualTo(Failure.PROVIDER_OVERLOADED);
        // A local Ollama that is not running: exactly the "ollama: Temporary failure in name
        // resolution" seen when the container is missing from the deployment.
        assertThat(AiFailoverPolicy.classify(springAiWrapped(
                new UnknownHostException("ollama: Temporary failure in name resolution"))))
                .isEqualTo(Failure.PROVIDER_OVERLOADED);
        assertThat(AiFailoverPolicy.classify(springAiWrapped(new ConnectException("Connection refused"))))
                .isEqualTo(Failure.PROVIDER_OVERLOADED);
    }

    @Test
    @DisplayName("credential and request errors never fail over — masking them hides the real fix")
    void refusesToMaskConfigurationErrors() {
        assertThat(AiFailoverPolicy.classify(new ClientException("401 . API key not valid. Please pass a valid API key.")))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(AiFailoverPolicy.classify(springAiWrapped(new AuthenticationException("invalid x-api-key"))))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(AiFailoverPolicy.classify(new ClientException("400 . Invalid JSON payload received.")))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(AiFailoverPolicy.classify(new NullPointerException("boom")))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(AiFailoverPolicy.classify(new RuntimeException()))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(Failure.NOT_FAILOVER.shouldFailover()).isFalse();
    }

    @Test
    @DisplayName("digits inside model names are not mistaken for HTTP statuses")
    void doesNotInventStatusCodes() {
        assertThat(AiFailoverPolicy.classify(new ClientException(
                "model gpt-4.1-mini-2025-04-14 produced an unexpected response shape")))
                .isEqualTo(Failure.NOT_FAILOVER);
        assertThat(AiFailoverPolicy.classify(new ClientException(
                "claude-sonnet-4-5-20250929 returned an unparseable chunk")))
                .isEqualTo(Failure.NOT_FAILOVER);
    }

    @Test
    @DisplayName("the real signal is found however deeply Spring AI wraps it")
    void walksTheCauseChain() {
        Throwable deep = new ClientException("429 . quota");
        for (int i = 0; i < 6; i++) {
            deep = new RuntimeException("wrapper " + i, deep);
        }
        assertThat(AiFailoverPolicy.classify(deep)).isEqualTo(Failure.QUOTA_EXHAUSTED);
    }

    @Test
    @DisplayName("a cyclic or absurdly deep cause chain terminates instead of hanging")
    void survivesPathologicalCauseChains() {
        // Throwable.initCause(this) is rejected by the JDK, so a genuine cycle can only arrive
        // from a type that overrides getCause().
        RuntimeException cyclic = new RuntimeException("cycle") {
            @Override
            public synchronized Throwable getCause() { return this; }
        };
        assertThat(AiFailoverPolicy.classify(cyclic)).isEqualTo(Failure.NOT_FAILOVER);

        Throwable tooDeep = new ClientException("429 . quota");
        for (int i = 0; i < 40; i++) {
            tooDeep = new RuntimeException("w" + i, tooDeep);
        }
        assertThat(AiFailoverPolicy.classify(tooDeep)).isEqualTo(Failure.NOT_FAILOVER);
    }
}
