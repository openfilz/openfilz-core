package org.openfilz.dms.service.ai;

import org.openfilz.dms.enums.AiProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What to show in the BYOK model picker: which of a provider's models are chat models, what order
 * to offer them in, and what to fall back to when the provider cannot be reached.
 * <p>
 * The fallback lists exist because a hardcoded list is exactly what went wrong before — OpenFilz
 * shipped {@code gemini-2.5-flash} as the Google default until Google retired it and every chat
 * started answering {@code 404 . This model ... is no longer available to new users}. A live list
 * from {@link AiModelDirectory} is therefore the normal path; these are the safety net for a
 * provider outage or a key that cannot list models, not the source of truth.
 */
public final class AiModelCatalog {

    private AiModelCatalog() {
    }

    /**
     * Known-good chat models per provider, best default first.
     * <p>
     * Also the ranking applied to a live list: whatever the provider returns, these float to the
     * top in this order, because the caller pre-fills the model field with the first entry and
     * that entry has to be a sensible default rather than whichever id the provider happened to
     * return first.
     */
    private static final List<String> GOOGLE_PREFERRED = List.of(
            "gemini-3.6-flash", "gemini-3.7-flash", "gemini-3.5-flash",
            "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
            "gemini-flash-latest", "gemini-flash-lite-latest", "gemini-pro-latest");

    private static final List<String> ANTHROPIC_PREFERRED = List.of(
            "claude-opus-5", "claude-sonnet-5", "claude-haiku-4-5");

    private static final List<String> OPENAI_PREFERRED = List.of(
            "gpt-4o", "gpt-4o-mini");

    /**
     * Substrings that mark a model as something other than a chat model.
     * <p>
     * Provider "list models" endpoints return the whole catalogue — text-to-speech, image and
     * video generation, embeddings, transcription, robotics. Google's response carries
     * {@code supportedGenerationMethods}, which is authoritative and checked first; several
     * non-chat models nonetheless advertise {@code generateContent}, and OpenAI-shaped endpoints
     * give no capability information at all, so the id is the only remaining signal.
     * <p>
     * Deliberately conservative: it is better to leave an odd model in the list — the field stays
     * free text, so nothing is lost — than to hide one the user actually wants.
     */
    private static final List<String> NON_CHAT_MARKERS = List.of(
            "embed", "-tts", "tts-", "text-to-speech", "whisper", "transcribe", "audio",
            "realtime", "dall-e", "imagen", "-image", "image-", "veo-", "lyria",
            "nano-banana", "robotics", "computer-use", "moderation", "rerank", "guard");

    /** The built-in list for a provider, used when the provider cannot be asked. */
    public static List<String> fallback(AiProvider provider) {
        return switch (provider) {
            case GOOGLE -> GOOGLE_PREFERRED;
            case ANTHROPIC -> ANTHROPIC_PREFERRED;
            case OPENAI -> OPENAI_PREFERRED;
            case OPENAI_COMPATIBLE -> List.of();
        };
    }

    /**
     * Whether an id looks like a chat model. {@code supportsGeneration} carries the provider's own
     * verdict where it has one ({@code null} when the provider says nothing).
     */
    public static boolean isChatModel(String modelId, Boolean supportsGeneration) {
        if (modelId == null || modelId.isBlank()) return false;
        if (Boolean.FALSE.equals(supportsGeneration)) return false;
        String id = modelId.toLowerCase(Locale.ROOT);
        for (String marker : NON_CHAT_MARKERS) {
            if (id.contains(marker)) return false;
        }
        return true;
    }

    /**
     * Order a provider's live list: the known-good models this provider actually returned first,
     * in preference order, then everything else in the order the provider gave.
     * <p>
     * Self-healing by construction — when a preferred id disappears from the provider it simply
     * stops matching, and the next one that does exist leads the list. That is the property the
     * hardcoded list lacked.
     */
    public static List<String> ordered(AiProvider provider, List<String> providerModels) {
        Set<String> available = new LinkedHashSet<>(providerModels);
        List<String> out = new ArrayList<>();
        for (String preferred : fallback(provider)) {
            if (available.remove(preferred)) {
                out.add(preferred);
            }
        }
        out.addAll(available);
        return List.copyOf(out);
    }
}
