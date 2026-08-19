package org.openfilz.dms.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.response.OnlyOfficeUserInfo;
import org.openfilz.dms.service.OnlyOfficeJwtService;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.Authentication;
import reactor.test.StepVerifier;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnlyOfficeAuthenticationManagerTest {

    @Mock
    private OnlyOfficeJwtService<OnlyOfficeUserInfo> jwtService;

    private OnlyOfficeAuthenticationManager manager;

    @BeforeEach
    void setUp() {
        manager = new OnlyOfficeAuthenticationManager(jwtService);
    }

    @Test
    void authenticate_notOnlyOfficeToken_returnsEmpty() {
        StepVerifier.create(manager.authenticate(mock(Authentication.class)))
                .verifyComplete();
        verifyNoInteractions(jwtService);
    }

    @Test
    void authenticate_missingRawToken_errors() {
        StepVerifier.create(manager.authenticate(new OnlyOfficeAuthenticationToken((String) null)))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void authenticate_invalidToken_errors() {
        when(jwtService.validateAndDecode("raw")).thenReturn(null);

        StepVerifier.create(manager.authenticate(new OnlyOfficeAuthenticationToken("raw")))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void authenticate_missingDocumentId_errors() {
        Map<String, Object> claims = Map.of("k", "v");
        when(jwtService.validateAndDecode("raw")).thenReturn(claims);
        when(jwtService.extractDocumentId(anyMap())).thenReturn(null);

        StepVerifier.create(manager.authenticate(new OnlyOfficeAuthenticationToken("raw")))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void authenticate_missingUserId_errors() {
        Map<String, Object> claims = Map.of("k", "v");
        when(jwtService.validateAndDecode("raw")).thenReturn(claims);
        when(jwtService.extractDocumentId(anyMap())).thenReturn(UUID.randomUUID());
        when(jwtService.extractUserId(anyMap())).thenReturn(null);

        StepVerifier.create(manager.authenticate(new OnlyOfficeAuthenticationToken("raw")))
                .expectError(BadCredentialsException.class)
                .verify();
    }

    @Test
    void authenticate_validToken_returnsAuthenticatedToken() {
        Map<String, Object> claims = Map.of("k", "v");
        UUID docId = UUID.randomUUID();
        when(jwtService.validateAndDecode("raw")).thenReturn(claims);
        when(jwtService.extractDocumentId(anyMap())).thenReturn(docId);
        when(jwtService.extractUserId(anyMap())).thenReturn("user-1");
        when(jwtService.extractUserName(anyMap())).thenReturn("Alice");
        when(jwtService.extractUserEmail(anyMap())).thenReturn("alice@example.com");

        StepVerifier.create(manager.authenticate(new OnlyOfficeAuthenticationToken("raw")))
                .expectNextMatches(auth -> auth.isAuthenticated()
                        && auth instanceof OnlyOfficeAuthenticationToken t
                        && "user-1".equals(t.getUserId())
                        && docId.equals(t.getDocumentId()))
                .verifyComplete();
    }
}
