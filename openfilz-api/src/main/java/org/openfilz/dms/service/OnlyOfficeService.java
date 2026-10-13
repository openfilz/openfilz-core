package org.openfilz.dms.service;

import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.DocumentLanguageInfo;
import org.openfilz.dms.dto.response.OnlyOfficeConfigResponse;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Service for OnlyOffice DocumentServer integration.
 * Handles editor configuration generation and document save callbacks.
 */
public interface OnlyOfficeService {

    /**
     * Generate the configuration needed to initialize the OnlyOffice editor for the
     * current (latest) content of a document.
     *
     * @param documentId The document ID to edit
     * @param canEdit    Whether the user can edit the document
     * @return Editor configuration including JWT token
     */
    default Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit) {
        return generateEditorConfig(documentId, canEdit, null);
    }

    /**
     * Generate the configuration needed to initialize the OnlyOffice editor.
     * <p>
     * When {@code versionId} is non-null the editor is opened on that specific stored
     * version (MinIO/S3 object version) and is always forced read-only — viewing history
     * must never overwrite the current content.
     *
     * @param documentId The document ID to edit
     * @param canEdit    Whether the user can edit the document (ignored when versionId is set)
     * @param versionId  The storage version to view, or {@code null} for the latest content
     * @return Editor configuration including JWT token
     */
    default Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit, String versionId) {
        return generateEditorConfig(documentId, canEdit, versionId, null);
    }

    /**
     * Same as {@link #generateEditorConfig(UUID, boolean, String)}, with the editor interface in
     * {@code lang} (the caller's OpenFilz language, e.g. {@code fr}); null or malformed = English.
     * OnlyOffice also spell-checks text that declares no language in this language.
     */
    Mono<OnlyOfficeConfigResponse> generateEditorConfig(UUID documentId, boolean canEdit, String versionId, String lang);

    /**
     * The proofing language a document declares and the one its text is written in. Only Word
     * (.docx) documents are supported; any other answers {@link DocumentLanguageInfo#unsupported()}.
     */
    Mono<DocumentLanguageInfo> getDocumentLanguage(UUID documentId);

    /**
     * Sets the proofing language of a Word document ({@code language} = BCP 47, e.g. {@code fr-FR})
     * and saves the result as a new version of the document.
     */
    Mono<DocumentLanguageInfo> setDocumentLanguage(UUID documentId, String language);

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
