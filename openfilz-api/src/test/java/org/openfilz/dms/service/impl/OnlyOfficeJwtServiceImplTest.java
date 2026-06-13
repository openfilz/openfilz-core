package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.config.OnlyOfficeProperties;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtExtractor;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnlyOfficeJwtServiceImplTest {

    @Mock(answer = Answers.RETURNS_DEEP_STUBS)
    private OnlyOfficeProperties properties;
    @Mock
    private OnlyOfficeJwtExtractor<OnlyOfficeUserInfo> extractor;

    private OnlyOfficeJwtServiceImpl newService(String secret) {
        when(properties.getJwt().getSecret()).thenReturn(secret);
        return new OnlyOfficeJwtServiceImpl(properties, extractor);
    }

    @Test
    void constructor_shortSecret_isPaddedWithoutError() {
        // secret < 32 chars triggers the padding branch
        assertNotNull(newService("short-secret"));
    }

    @Test
    void constructor_nullSecret_fallsBackToDefault() {
        assertNotNull(newService(null));
    }

    @Test
    void generateToken_whenJwtDisabled_returnsNull() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        when(properties.getJwt().isEnabled()).thenReturn(false);

        assertNull(service.generateToken(Map.of("k", "v")));
    }

    @Test
    void generateAccessToken_whenJwtDisabled_returnsNull() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        when(properties.getJwt().isEnabled()).thenReturn(false);

        assertNull(service.generateAccessToken(UUID.randomUUID(), mock(OnlyOfficeUserInfo.class)));
    }

    @Test
    void validateAndDecode_nullOrEmpty_returnsNull() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");

        assertNull(service.validateAndDecode(null));
        assertNull(service.validateAndDecode(""));
    }

    @Test
    void validateAndDecode_invalidToken_returnsNull() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");

        assertNull(service.validateAndDecode("not.a.jwt"));
        assertFalse(service.isValid("not.a.jwt"));
    }

    @Test
    void extractDocumentId_directClaim_parsesUuid() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        UUID id = UUID.randomUUID();

        assertEquals(id, service.extractDocumentId(Map.of("documentId", id.toString())));
    }

    @Test
    void extractDocumentId_payloadKeyFallback_parsesUuidPrefix() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        UUID id = UUID.randomUUID();
        Map<String, Object> claims = Map.of("payload", Map.of("key", id + "_1700000000"));

        assertEquals(id, service.extractDocumentId(claims));
    }

    @Test
    void extractUserId_directClaim() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        assertEquals("u-1", service.extractUserId(Map.of("userId", "u-1")));
    }

    @Test
    void extractUserId_payloadActionsFallback() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        Map<String, Object> claims = Map.of("payload",
                Map.of("actions", java.util.List.of(Map.of("userid", "u-2"))));
        assertEquals("u-2", service.extractUserId(claims));
    }

    @Test
    void extractUserNameAndEmail_returnNullWhenAbsent() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        assertNull(service.extractUserName(Map.of()));
        assertNull(service.extractUserEmail(Map.of()));
    }

    @Test
    void generateAndValidate_roundTrip_returnsClaims() {
        OnlyOfficeJwtServiceImpl service = newService("a-sufficiently-long-secret-key-1234567890");
        when(properties.getJwt().isEnabled()).thenReturn(true);
        when(properties.getJwt().getExpirationSeconds()).thenReturn(3600L);

        String token = service.generateToken(Map.of("documentId", "abc"));
        assertNotNull(token);

        Map<String, Object> claims = service.validateAndDecode(token);
        assertNotNull(claims);
        assertEquals("abc", claims.get("documentId"));
    }
}
