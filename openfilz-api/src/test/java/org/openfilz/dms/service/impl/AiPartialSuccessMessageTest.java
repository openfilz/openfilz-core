package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.service.ai.AiFailoverPolicy;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link AiChatServiceImpl#buildPartialSuccessMessage} — the message returned when a
 * mutating tool committed but the model then failed to produce a summary (e.g. the Gemini free-tier
 * 5-req/min quota tripping mid-agent-loop). The changes are real, so the message must confirm them
 * rather than read as a failure.
 */
class AiPartialSuccessMessageTest {

    @Test
    void listsCompletedActionAndQuotaReason() {
        String msg = AiChatServiceImpl.buildPartialSuccessMessage(
                List.of("Moved 1 item(s) to 'latest'"), AiFailoverPolicy.Failure.QUOTA_EXHAUSTED);
        assertThat(msg)
                .contains("- Moved 1 item(s) to 'latest'")
                .contains("rate limit was reached")
                .contains("Your changes are saved");
    }

    @Test
    void pluralisesAndListsMultipleActions() {
        String msg = AiChatServiceImpl.buildPartialSuccessMessage(
                List.of("Created folder 'latest'", "Moved 1 item(s) to 'latest'"),
                AiFailoverPolicy.Failure.QUOTA_EXHAUSTED);
        assertThat(msg)
                .contains("following changes:")
                .contains("- Created folder 'latest'")
                .contains("- Moved 1 item(s) to 'latest'");
    }

    @Test
    void tailorsReasonPerFailureType() {
        assertThat(AiChatServiceImpl.buildPartialSuccessMessage(
                List.of("x"), AiFailoverPolicy.Failure.MODEL_UNAVAILABLE)).contains("currently unavailable");
        assertThat(AiChatServiceImpl.buildPartialSuccessMessage(
                List.of("x"), AiFailoverPolicy.Failure.PROVIDER_OVERLOADED)).contains("overloaded");
        assertThat(AiChatServiceImpl.buildPartialSuccessMessage(
                List.of("x"), AiFailoverPolicy.Failure.NOT_FAILOVER)).contains("could not finish");
    }

    @Test
    void fallsBackGracefullyWhenNoActionsWereRecorded() {
        String msg = AiChatServiceImpl.buildPartialSuccessMessage(
                List.of(), AiFailoverPolicy.Failure.QUOTA_EXHAUSTED);
        assertThat(msg)
                .contains("applied the requested change")
                .contains("rate limit was reached");
    }
}
