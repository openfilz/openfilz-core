package org.openfilz.dms.security;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OnlyOfficeAuthenticationTokenTest {

    @Test
    void unauthenticatedToken_hasNullPrincipalAndRawCredentials() {
        OnlyOfficeAuthenticationToken token = new OnlyOfficeAuthenticationToken("raw-token");

        assertFalse(token.isAuthenticated());
        assertEquals("raw-token", token.getRawToken());
        assertEquals("raw-token", token.getCredentials());
        assertNull(token.getPrincipal());
        assertNull(token.getName());
    }

    @Test
    void authenticatedToken_exposesUserDetails() {
        UUID docId = UUID.randomUUID();
        OnlyOfficeAuthenticationToken token =
                new OnlyOfficeAuthenticationToken("user-1", "Alice", "alice@example.com", docId, "raw");

        assertTrue(token.isAuthenticated());
        assertEquals("user-1", token.getUserId());
        assertEquals("user-1", token.getPrincipal());
        assertEquals("alice@example.com", token.getUserEmail());
        assertEquals(docId, token.getDocumentId());
        assertEquals("Alice", token.getName());
    }

    @Test
    void getName_fallsBackToUserId_whenNameMissing() {
        OnlyOfficeAuthenticationToken token =
                new OnlyOfficeAuthenticationToken("user-2", null, null, UUID.randomUUID(), "raw");

        assertEquals("user-2", token.getName());
    }
}
