package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContentTypeMapperTest {

    @Test
    void extensionsMatching_prefixAndExactPatterns() {
        var images = ContentTypeMapper.extensionsMatching(List.of("image/%"));
        assertTrue(images.containsAll(List.of("png", "jpg", "jpeg", "gif", "webp", "tif")), images.toString());
        assertFalse(images.contains("pdf"));

        assertEquals(java.util.Set.of("pdf"), ContentTypeMapper.extensionsMatching(List.of(" APPLICATION/PDF ")));
        assertTrue(ContentTypeMapper.extensionsMatching(List.of("application/zip")).contains("zip"));
        assertTrue(ContentTypeMapper.extensionsMatching(List.of("application/vnd.oasis.opendocument.text")).contains("odt"));
    }

    @Test
    void extensionsMatching_nothingToMatch() {
        assertTrue(ContentTypeMapper.extensionsMatching(null).isEmpty());
        assertTrue(ContentTypeMapper.extensionsMatching(List.of()).isEmpty());
        assertTrue(ContentTypeMapper.extensionsMatching(List.of("application/x-unknown")).isEmpty());
    }

    @Test
    void matchesPattern() {
        assertTrue(ContentTypeMapper.matchesPattern("image/PNG", "image/%"));
        assertTrue(ContentTypeMapper.matchesPattern("text/plain", "text/plain"));
        assertFalse(ContentTypeMapper.matchesPattern("text/plain", "text/"));
        assertFalse(ContentTypeMapper.matchesPattern(null, "image/%"));
    }

    @Test
    void getContentType_isUnchanged() {
        // The extra table only serves pattern matching: downloads keep their historical type
        assertEquals("application/octet-stream", ContentTypeMapper.getContentType("zip"));
    }
}
