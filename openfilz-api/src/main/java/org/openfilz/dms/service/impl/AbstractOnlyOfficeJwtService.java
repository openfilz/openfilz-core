package org.openfilz.dms.service.impl;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.response.IUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;
import org.openfilz.dms.service.OnlyOfficeJwtService;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.openfilz.dms.service.OnlyOfficeJwtExtractor.USER_EMAIL_CLAIM;
import static org.openfilz.dms.service.OnlyOfficeJwtExtractor.USER_NAME_CLAIM;

/**
 * Implementation of OnlyOfficeJwtService using HMAC-SHA256.
 * Generates and validates JWT tokens for OnlyOffice DocumentServer integration.
 */
@Slf4j
public abstract class AbstractOnlyOfficeJwtService<T extends IUserInfo> implements OnlyOfficeJwtService<T> {

    private static final String DOCUMENT_ID_CLAIM = "documentId";
    private static final String TYPE_CLAIM = "type";
    private static final String ACCESS_TOKEN_TYPE = "access";

    private final OnlyOfficeProperties properties;
    private final SecretKey secretKey;
    private final OnlyOfficeJwtExtractor<T> extractor;

    public AbstractOnlyOfficeJwtService(OnlyOfficeProperties properties, OnlyOfficeJwtExtractor<T> extractor) {
        this.properties = properties;
        this.extractor = extractor;
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
    public String generateAccessToken(UUID documentId, T userInfo) {
        if (!properties.getJwt().isEnabled()) {
            return null;
        }

        Date now = new Date();
        Date expiration = new Date(now.getTime() + properties.getJwt().getExpirationSeconds() * 1000);

        return extractor.newJwt(userInfo)
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

    public UUID extractDocumentId(Map<String, Object> claims) {
        Object documentId = claims.get("documentId");
        if(documentId == null) {
            Map<String, Object> p1 = (Map<String, Object>) claims.get("payload");
            String id = (String)p1.get("key");
            return UUID.fromString(id.substring(0, id.indexOf("_")));
        }
        return UUID.fromString(documentId.toString());
    }



    @Override
    public boolean isValid(String token) {
        return validateAndDecode(token) != null;
    }

    public String extractUserId(Map<String, Object> claims) {
        Object userId = claims.get("userId");
        if(userId == null) {
            Map<String, Object> p1 = (Map<String, Object>) claims.get("payload");
            List<Map<String, Object>> actions = (List<Map<String, Object>>) p1.get("actions");
            return actions.get(0).get("userid").toString();
        }
        return userId.toString();
    }


    @Override
    public String extractUserName(Map<String, Object> claims) {
        Object userNameObj = claims.get(USER_NAME_CLAIM);
        return userNameObj != null ? userNameObj.toString() : null;
    }

    @Override
    public String extractUserEmail(Map<String, Object> claims) {
        Object emailObj = claims.get(USER_EMAIL_CLAIM);
        return emailObj != null ? emailObj.toString() : null;
    }
}
