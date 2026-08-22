package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.ListAiModelsRequest;
import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
import org.openfilz.dms.dto.response.AiModelsResponse;
import org.openfilz.dms.dto.response.AiSettingsResponse;
import reactor.core.publisher.Mono;

/**
 * Per-user chat-LLM settings (BYOK): read, save, reset, and test-connection.
 * The API key is write-only — responses only carry {@code hasApiKey} + a display suffix.
 */
public interface AiSettingsService {

    Mono<AiSettingsResponse> getSettings(String userEmail);

    Mono<AiSettingsResponse> saveSettings(String userEmail, SaveAiSettingsRequest request);

    Mono<Void> deleteSettings(String userEmail);

    /**
     * Probe the provider with a minimal completion using the submitted settings
     * (falling back to the stored API key when the request carries none).
     */
    Mono<AiConnectionTestResult> testConnection(String userEmail, SaveAiSettingsRequest request);

    /**
     * The chat models the provider currently offers for this key, so the picker reflects what
     * exists today rather than what existed at release time. Falls back to a built-in list — never
     * an error — when the provider cannot be asked: a model picker that cannot list is still
     * usable, since the field takes free text.
     */
    Mono<AiModelsResponse> listModels(String userEmail, ListAiModelsRequest request);
}
