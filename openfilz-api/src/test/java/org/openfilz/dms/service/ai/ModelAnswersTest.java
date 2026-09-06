package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelAnswersTest {

    @Test
    @DisplayName("a Gemini answer split in several parts is read whole, in order — content() would keep the first part only")
    void joinsEveryGeneration() {
        ChatResponse response = new ChatResponse(List.of(
                new Generation(new AssistantMessage("{\"category\": \"invoice\", \"summary\": \"Invoice 2025-0457")),
                new Generation(new AssistantMessage(" from Apple Czech\", \"keywords\": [\"invoice\"]}"))));

        assertThat(ModelAnswers.text(response, "INSIGHTS", 4096))
                .isEqualTo("{\"category\": \"invoice\", \"summary\": \"Invoice 2025-0457 from Apple Czech\", \"keywords\": [\"invoice\"]}");
        assertThat(ModelAnswers.truncated(response)).isFalse();
    }

    @Test
    @DisplayName("an answer cut at the token cap is recognised whatever the provider calls the finish reason")
    void recognisesTruncation() {
        assertThat(ModelAnswers.truncated(response("MAX_TOKENS"))).isTrue();
        assertThat(ModelAnswers.truncated(response("length"))).isTrue();
        assertThat(ModelAnswers.truncated(response("max_tokens"))).isTrue();
        assertThat(ModelAnswers.truncated(response("STOP"))).isFalse();
        assertThat(ModelAnswers.truncated(response("end_turn"))).isFalse();
        assertThat(ModelAnswers.truncated(response(null))).isFalse();
        // The text is still returned: the caller decides what to do with the fragment
        assertThat(ModelAnswers.text(response("MAX_TOKENS"), "AUTOFILE", 512)).isEqualTo("{\"target\": \"Inv");
    }

    @Test
    @DisplayName("no response, no generations, no text: an empty answer, never a null")
    void toleratesEmptyResponses() {
        assertThat(ModelAnswers.text(null, "INSIGHTS", 4096)).isEmpty();
        assertThat(ModelAnswers.text(new ChatResponse(List.of()), "INSIGHTS", 4096)).isEmpty();
        assertThat(ModelAnswers.truncated(null)).isFalse();
    }

    private static ChatResponse response(String finishReason) {
        ChatGenerationMetadata metadata = finishReason == null
                ? ChatGenerationMetadata.NULL
                : ChatGenerationMetadata.builder().finishReason(finishReason).build();
        return new ChatResponse(List.of(new Generation(new AssistantMessage("{\"target\": \"Inv"), metadata)));
    }
}
