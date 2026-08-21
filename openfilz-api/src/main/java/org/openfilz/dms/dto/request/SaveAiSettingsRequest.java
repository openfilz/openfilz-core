package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.AiProvider;

/**
 * Save (upsert) the connected user's personal chat-LLM settings (BYOK).
 * {@code apiKey} is write-only: omit it (null/blank) to keep the previously stored key.
 */
public record SaveAiSettingsRequest(
        @NotNull @Schema(description = "Chat LLM provider") AiProvider provider,
        @Schema(description = "Model id, e.g. claude-opus-5, gpt-4o, gemini-2.5-flash") String model,
        @Schema(description = "Base URL — required for OPENAI_COMPATIBLE, ignored otherwise") String baseUrl,
        @Schema(description = "API key (write-only). Omit to keep the stored key.") String apiKey) {
}
