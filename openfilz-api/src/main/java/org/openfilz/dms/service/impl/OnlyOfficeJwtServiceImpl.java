package org.openfilz.dms.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Implementation of OnlyOfficeJwtService using HMAC-SHA256.
 * Generates and validates JWT tokens for OnlyOffice DocumentServer integration.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "onlyoffice.enabled", havingValue = "true")
public class OnlyOfficeJwtServiceImpl implements OnlyOfficeJwtService {

    private static final String DOCUMENT_ID_CLAIM = "documentId";
    private static final String TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final OnlyOfficeProperties properties;
    private final SecretKey secretKey;

    public OnlyOfficeJwtServiceImpl(OnlyOfficeProperties properties) {
        this.properties = properties;
        // Ensure the secret is at least 256 bits (32 bytes) for HS256
        String secret = properties.getJwt().getSecret();
        if (secret == null || secret.length() < 32) {
            log.warn("OnlyOffice JWT secret is too short. Padding to 32 characters.");
            secret = String.format("%-32s", secret != null ? secret : "default-secret").substring(0, 32);
        }
        this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String generateToken(Map<String, Object> payload) {
        if (!properties.getJwt().isEnabled()) {
            return null;
        }

        Date now = new Date();
        Date expiration = new Date(now.getTime() + properties.getJwt().getExpirationSeconds() * 1000);

        return Jwts.builder()
                .claims(payload)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    @Override
    public String generateAccessToken(UUID documentId) {
        if (!properties.getJwt().isEnabled()) {
            return null;
        }

        Date now = new Date();
        Date expiration = new Date(now.getTime() + properties.getJwt().getExpirationSeconds() * 1000);

        return Jwts.builder()
                .claim(DOCUMENT_ID_CLAIM, documentId.toString())
                .claim(TYPE_CLAIM, ACCESS_TOKEN_TYPE)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(secretKey)
                .compact();
    }

    @Override
    public Map<String, Object> validateAndDecode(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }

        try {
            Claims claims = Jwts.parser()
                    .verifyWith(secretKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();

            return new HashMap<>(claims);
        } catch (Exception e) {
            log.warn("Failed to validate OnlyOffice JWT token: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public UUID extractDocumentId(String token) {
        Map<String, Object> claims = validateAndDecode(token);
        if (claims == null) {
            return null;
        }

        Object documentIdObj = claims.get(DOCUMENT_ID_CLAIM);
        if (documentIdObj == null) {
            return null;
        }

        try {
            return UUID.fromString(documentIdObj.toString());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid document ID in token: {}", documentIdObj);
            return null;
        }
    }

    @Override
    public boolean isValid(String token) {
        return validateAndDecode(token) != null;
    }
}
