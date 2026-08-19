package org.openfilz.dms.security.impl;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class DefaultJwtTokenParserTest {

    private final DefaultJwtTokenParser parser = new DefaultJwtTokenParser(mock(ReactiveJwtDecoder.class));

    @Test
    void extractTokenValue_validBearer_returnsToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer abc123-._~+/==");

        assertEquals("abc123-._~+/==", parser.extactTokenValue(headers));
    }

    @Test
    void extractTokenValue_missingHeader_throws() {
        assertThrows(AccessDeniedException.class, () -> parser.extactTokenValue(new HttpHeaders()));
    }

    @Test
    void extractTokenValue_notBearer_throws() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Basic dXNlcjpwYXNz");

        assertThrows(AccessDeniedException.class, () -> parser.extactTokenValue(headers));
    }

    @Test
    void extractTokenValue_malformedBearer_throws() {
        HttpHeaders headers = new HttpHeaders();
        headers.add("Authorization", "Bearer @@@not-valid@@@");

        assertThrows(AccessDeniedException.class, () -> parser.extactTokenValue(headers));
    }
}
