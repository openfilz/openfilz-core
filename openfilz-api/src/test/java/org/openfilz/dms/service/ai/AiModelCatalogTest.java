package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.AiProvider;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which of a provider's models reach the BYOK picker, and in what order.
 * <p>
 * The order is behaviour, not presentation: the settings page pre-fills the model field with the
 * first entry, so whatever leads the list is what a user gets by default. OpenFilz shipped
 * {@code gemini-2.5-flash} in that position until Google retired it and every BYOK chat answered
 * {@code 404 ... no longer available to new users}.
 */
class AiModelCatalogTest {

    /** Ids a real Gemini ListModels returns on a free-tier key. */
    private static final List<String> GOOGLE_LIVE = List.of(
            "gemini-2.5-flash", "gemini-2.5-pro", "gemma-4-31b-it", "gemini-flash-latest",
            "gemini-pro-latest", "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.5-flash-lite",
            "gemini-3.6-flash", "gemini-3.7-flash");

    @Test
    @DisplayName("the pre-filled default leads the list, whatever order the provider used")
    void preferredModelsLeadTheList() {
        List<String> ordered = AiModelCatalog.ordered(AiProvider.GOOGLE, GOOGLE_LIVE);

        assertThat(ordered).first().isEqualTo("gemini-3.6-flash");
        assertThat(ordered).startsWith("gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.5-flash");
        assertThat(ordered).containsExactlyInAnyOrderElementsOf(GOOGLE_LIVE);
        assertThat(ordered).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("when the preferred model is retired the next known-good one leads — no release needed")
    void orderingSelfHealsWhenAModelDisappears() {
        List<String> withoutDefault = new ArrayList<>(GOOGLE_LIVE);
        withoutDefault.remove("gemini-3.6-flash");

        assertThat(AiModelCatalog.ordered(AiProvider.GOOGLE, withoutDefault))
                .first().isEqualTo("gemini-3.7-flash");
    }

    @Test
    @DisplayName("a provider offering nothing familiar still gets its own list, unfiltered by preference")
    void unknownModelsAreStillOffered() {
        List<String> exotic = List.of("some-future-model-9", "another-one");

        assertThat(AiModelCatalog.ordered(AiProvider.GOOGLE, exotic)).isEqualTo(exotic);
    }

    @Test
    @DisplayName("the provider's own capability verdict wins over the id")
    void providerCapabilityDecidesWhereItIsGiven() {
        assertThat(AiModelCatalog.isChatModel("gemini-3.6-flash", true)).isTrue();
        assertThat(AiModelCatalog.isChatModel("gemini-3.6-flash", false)).isFalse();
        // Null means the provider said nothing — OpenAI-shaped endpoints carry no capability info.
        assertThat(AiModelCatalog.isChatModel("gpt-4o", null)).isTrue();
    }

    @Test
    @DisplayName("non-chat models are kept out of a chat model picker")
    void nonChatModelsAreFilteredOut() {
        // These all advertise generateContent, so only the id distinguishes them.
        assertThat(AiModelCatalog.isChatModel("gemini-2.5-flash-preview-tts", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("gemini-2.5-flash-image", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("nano-banana-pro-preview", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("lyria-3-pro-preview", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("gemini-robotics-er-2-preview", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("gemini-2.5-computer-use-preview-10-2025", true)).isFalse();
        assertThat(AiModelCatalog.isChatModel("text-embedding-3-small", null)).isFalse();
        assertThat(AiModelCatalog.isChatModel("whisper-1", null)).isFalse();
        assertThat(AiModelCatalog.isChatModel("dall-e-3", null)).isFalse();
        assertThat(AiModelCatalog.isChatModel("omni-moderation-latest", null)).isFalse();
    }

    @Test
    @DisplayName("the filter stays conservative — an unfamiliar chat model is not hidden")
    void unfamiliarChatModelsSurvive() {
        assertThat(AiModelCatalog.isChatModel("gemma-4-31b-it", true)).isTrue();
        assertThat(AiModelCatalog.isChatModel("gemini-flash-latest", true)).isTrue();
        assertThat(AiModelCatalog.isChatModel("mistral-large-2411", null)).isTrue();
        assertThat(AiModelCatalog.isChatModel("", null)).isFalse();
        assertThat(AiModelCatalog.isChatModel(null, null)).isFalse();
    }

    @Test
    @DisplayName("no fallback list offers a model known to be retired")
    void fallbackListsAreCurrent() {
        assertThat(AiModelCatalog.fallback(AiProvider.GOOGLE))
                .first().isEqualTo("gemini-3.6-flash");
        assertThat(AiModelCatalog.fallback(AiProvider.GOOGLE))
                .doesNotContain("gemini-2.5-flash", "gemini-2.5-pro");
        // Nothing sensible to guess for an arbitrary gateway — the picker asks it instead.
        assertThat(AiModelCatalog.fallback(AiProvider.OPENAI_COMPATIBLE)).isEmpty();
        for (AiProvider provider : AiProvider.values()) {
            assertThat(AiModelCatalog.fallback(provider)).doesNotHaveDuplicates();
        }
    }
}
