package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for OnlyOffice DocumentServer integration.
 * Handles editor configuration generation and document save callbacks.
 */
public interface OnlyOfficeService {

    /**
     * Generate the configuration needed to initialize the OnlyOffice editor.
     *
     * @param documentId The document ID to edit
     * @param userId     The user ID opening the editor (optional)
     * @param userName   The user name to display (optional)
     * @param canEdit    Whether the user can edit the document
     * @return Editor configuration including JWT token
     */
    Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, String userId, String userName, boolean canEdit);

    /**
     * Handle a callback from OnlyOffice DocumentServer.
     * Processes save events and updates the document in storage.
     *
     * @param documentId The document ID
     * @param callback   The callback request from OnlyOffice
     * @return Mono completing when callback is processed
     */
    Mono<Void> handleCallback(UUID documentId, OnlyOfficeCallbackRequest callback);

    /**
     * Check if a file name is supported by OnlyOffice.
     *
     * @param fileName The file name to check
     * @return true if the file type is supported
     */
    boolean isSupported(String fileName);

    /**
     * Check if OnlyOffice integration is enabled.
     *
     * @return true if enabled
     */
    boolean isEnabled();
}
