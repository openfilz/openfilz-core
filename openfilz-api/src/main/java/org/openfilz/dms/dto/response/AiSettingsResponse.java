package org.openfilz.dms.dto.response;

import lombok.Builder;

/**
 * The connected user's personal chat-LLM settings. The API key is never returned —
 * only {@code hasApiKey} and its last characters ({@code keySuffix}) for display.
 * {@code provider} is null when the user runs on the server default.
 */
@Builder
public record AiSettingsResponse(
        boolean enabled,
        String provider,
        String model,
        String baseUrl,
        boolean hasApiKey,
        String keySuffix,
        String defaultProvider,
        String defaultModel) {
}
