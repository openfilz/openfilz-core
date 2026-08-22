package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.AiProvider;

/**
 * Ask a provider which chat models the given key can actually use (BYOK model picker).
 * <p>
 * The key travels in the body rather than a query string on purpose: query strings end up in
 * access logs and browser history. {@code apiKey} may be omitted to use the stored key, which is
 * what the settings page does once a key has been saved.
 */
public record ListAiModelsRequest(
        @NotNull @Schema(description = "Chat LLM provider") AiProvider provider,
        @Schema(description = "Base URL — required for OPENAI_COMPATIBLE, ignored otherwise") String baseUrl,
        @Schema(description = "API key (write-only). Omit to use the stored key.") String apiKey) {
}
