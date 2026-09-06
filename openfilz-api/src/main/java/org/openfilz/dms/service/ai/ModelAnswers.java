package org.openfilz.dms.service.ai;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.Locale;

/**
 * The text of a model's answer to one of the JSON-contract calls (tier-2 insights, smart filing
 * stage 2), read from the whole {@link ChatResponse} rather than through {@code content()}.
 * <p>
 * Two things {@code content()} hides. Gemini 3 answers in several parts (each part carries its
 * own thought signature) and Spring AI maps every part to its own {@link Generation}, while
 * {@code content()} returns the first generation only — a long answer came back as a fragment
 * that started in the middle of the JSON object. And a thinking model counts its thoughts
 * against {@code maxOutputTokens}: an answer cap sized for the JSON contract alone left Gemini a
 * few dozen tokens of visible text, rejected as "no JSON object in the answer" with nothing in
 * the log naming the cause. The finish reason says so; it is logged here.
 */
@Slf4j
public final class ModelAnswers {

    private ModelAnswers() {
    }

    /**
     * Every generation's text, in order, as one answer.
     *
     * @param response the model's response
     * @param tag      the caller's log tag ({@code INSIGHTS}, {@code AUTOFILE})
     * @param cap      the {@code maxTokens} the call passed, named in the truncation warning
     */
    public static String text(ChatResponse response, String tag, int cap) {
        if (response == null || response.getResults() == null || response.getResults().isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Generation generation : response.getResults()) {
            if (generation != null && generation.getOutput() != null && generation.getOutput().getText() != null) {
                text.append(generation.getOutput().getText());
            }
        }
        if (truncated(response)) {
            log.warn("[{}] the model stopped at the {}-token answer cap ({} visible character(s)) — a thinking model "
                            + "counts its thoughts against that cap: raise openfilz.ai.max-answer-tokens or lower the "
                            + "model's thinking budget",
                    tag, cap, text.length());
        }
        return text.toString();
    }

    /** True when any generation ended because the output limit was reached, whatever the provider calls it. */
    public static boolean truncated(ChatResponse response) {
        if (response == null || response.getResults() == null) {
            return false;
        }
        for (Generation generation : response.getResults()) {
            ChatGenerationMetadata metadata = generation == null ? null : generation.getMetadata();
            String reason = metadata == null ? null : metadata.getFinishReason();
            if (reason == null) {
                continue;
            }
            String normalised = reason.toUpperCase(Locale.ROOT);
            // Google: MAX_TOKENS; OpenAI / Anthropic-style: LENGTH / MAX_TOKENS; Ollama: LENGTH
            if (normalised.contains("MAX_TOKENS") || normalised.equals("LENGTH")) {
                return true;
            }
        }
        return false;
    }
}
