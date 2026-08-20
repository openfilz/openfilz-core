package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.SaveAiSettingsRequest;
import org.openfilz.dms.dto.response.AiConnectionTestResult;
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
}
