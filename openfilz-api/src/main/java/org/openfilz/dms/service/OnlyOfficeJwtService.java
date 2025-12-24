package org.openfilz.dms.service;

import java.util.Map;
import java.util.UUID;

/**
 * Service for generating and validating JWT tokens for OnlyOffice integration.
 * Uses a shared secret with OnlyOffice DocumentServer for authentication.
 */
public interface OnlyOfficeJwtService {

    /**
     * Generate a JWT token for the OnlyOffice editor configuration.
     *
     * @param payload The configuration payload to sign
     * @return Signed JWT token
     */
    String generateToken(Map<String, Object> payload);

    /**
     * Generate a short-lived access token for document download.
     * This token allows OnlyOffice DocumentServer to fetch the document.
     *
     * @param documentId The document ID to include in the token
     * @return Signed JWT access token
     */
    String generateAccessToken(UUID documentId);

    /**
     * Validate and decode a JWT token.
     *
     * @param token The JWT token to validate
     * @return Decoded payload if valid, null if invalid
     */
    Map<String, Object> validateAndDecode(String token);

    /**
     * Extract the document ID from an access token.
     *
     * @param token The access token
     * @return Document ID if valid, null if invalid
     */
    UUID extractDocumentId(String token);

    /**
     * Check if a token is valid.
     *
     * @param token The JWT token to validate
     * @return true if valid, false otherwise
     */
    boolean isValid(String token);
}
