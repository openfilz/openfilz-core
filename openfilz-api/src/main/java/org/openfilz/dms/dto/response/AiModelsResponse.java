package org.openfilz.dms.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.openfilz.dms.enums.AiProvider;

import java.util.List;

/**
 * The chat models a provider currently offers for a given key.
 *
 * @param source LIVE when the list came from the provider, FALLBACK when the call failed and the
 *               built-in list is being offered instead — the picker stays usable either way, and
 *               the caller can say which it is showing.
 */
public record AiModelsResponse(
        @Schema(description = "Provider the list belongs to") AiProvider provider,
        @Schema(description = "Model ids, best default first") List<String> models,
        @Schema(description = "LIVE or FALLBACK") Source source,
        @Schema(description = "Why the provider list is unavailable, when source is FALLBACK") String message) {

    public enum Source { LIVE, FALLBACK }

    public static AiModelsResponse live(AiProvider provider, List<String> models) {
        return new AiModelsResponse(provider, models, Source.LIVE, null);
    }

    public static AiModelsResponse fallback(AiProvider provider, List<String> models, String message) {
        return new AiModelsResponse(provider, models, Source.FALLBACK, message);
    }
}
