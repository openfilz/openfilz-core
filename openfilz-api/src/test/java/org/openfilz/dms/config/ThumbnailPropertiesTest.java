package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailPropertiesTest {

    private final ThumbnailProperties props = new ThumbnailProperties();

    // --- isContentTypeSupported ---

    @Test
    void isContentTypeSupported_withNull_returnsFalse() {
        assertFalse(props.isContentTypeSupported(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp", "image/tiff"})
    void isContentTypeSupported_withImageTypes_returnsTrue(String contentType) {
        assertTrue(props.isContentTypeSupported(contentType));
    }

    @Test
    void isContentTypeSupported_withPdf_returnsTrue() {
        assertTrue(props.isContentTypeSupported("application/pdf"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    })
    void isContentTypeSupported_withOfficeTypes_returnsTrue(String contentType) {
        assertTrue(props.isContentTypeSupported(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/markdown", "text/html", "text/css", "text/csv", "text/xml"})
    void isContentTypeSupported_withTextTypes_returnsTrue(String contentType) {
        assertTrue(props.isContentTypeSupported(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/javascript", "application/xml", "application/yaml", "application/sql"})
    void isContentTypeSupported_withTextApplicationTypes_returnsTrue(String contentType) {
        assertTrue(props.isContentTypeSupported(contentType));
    }

    @Test
    void isContentTypeSupported_withUnsupportedType_returnsFalse() {
        assertFalse(props.isContentTypeSupported("application/zip"));
    }

    @Test
    void isContentTypeSupported_caseInsensitive() {
        assertTrue(props.isContentTypeSupported("IMAGE/JPEG"));
    }

    @Test
    void isContentTypeSupported_withNullSupportedContentTypes_returnsFalse() {
        props.setSupportedContentTypes(null);
        assertFalse(props.isContentTypeSupported("image/jpeg"));
    }

    // --- shouldUseGotenberg ---

    @Test
    void shouldUseGotenberg_withNull_returnsFalse() {
        assertFalse(props.shouldUseGotenberg(null));
    }

    @Test
    void shouldUseGotenberg_withDocx_returnsTrue() {
        assertTrue(props.shouldUseGotenberg("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    void shouldUseGotenberg_withOdt_returnsTrue() {
        assertTrue(props.shouldUseGotenberg("application/vnd.oasis.opendocument.text"));
    }

    @Test
    void shouldUseGotenberg_withPdf_returnsFalse() {
        assertFalse(props.shouldUseGotenberg("application/pdf"));
    }

    @Test
    void shouldUseGotenberg_withImage_returnsFalse() {
        assertFalse(props.shouldUseGotenberg("image/jpeg"));
    }

    // --- shouldUsePdfBox ---

    @Test
    void shouldUsePdfBox_withNull_returnsFalse() {
        assertFalse(props.shouldUsePdfBox(null));
    }

    @Test
    void shouldUsePdfBox_withPdf_returnsTrue() {
        assertTrue(props.shouldUsePdfBox("application/pdf"));
    }

    @Test
    void shouldUsePdfBox_withNonPdf_returnsFalse() {
        assertFalse(props.shouldUsePdfBox("image/jpeg"));
    }

    // --- shouldUseTextRenderer ---

    @Test
    void shouldUseTextRenderer_withNull_returnsFalse() {
        assertFalse(props.shouldUseTextRenderer(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/plain", "text/markdown", "text/html", "text/css", "text/x-java-source"})
    void shouldUseTextRenderer_withTextSlashTypes_returnsTrue(String contentType) {
        assertTrue(props.shouldUseTextRenderer(contentType));
    }

    @ParameterizedTest
    @ValueSource(strings = {"application/json", "application/javascript", "application/xml", "application/yaml", "application/sql", "application/graphql"})
    void shouldUseTextRenderer_withTextApplicationTypes_returnsTrue(String contentType) {
        assertTrue(props.shouldUseTextRenderer(contentType));
    }

    @Test
    void shouldUseTextRenderer_withBinaryType_returnsFalse() {
        assertFalse(props.shouldUseTextRenderer("application/zip"));
    }

    @Test
    void shouldUseTextRenderer_withImageType_returnsFalse() {
        assertFalse(props.shouldUseTextRenderer("image/jpeg"));
    }

    // --- shouldUseWebpConversion ---

    @Test
    void shouldUseWebpConversion_withImageJpeg_returnsTrue() {
        assertTrue(props.shouldUseWebpConversion("image/jpeg"));
    }

    @Test
    void shouldUseWebpConversion_withPdf_returnsFalse() {
        assertFalse(props.shouldUseWebpConversion("application/pdf"));
    }

    @Test
    void shouldUseWebpConversion_withDocx_returnsFalse() {
        assertFalse(props.shouldUseWebpConversion("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));
    }

    @Test
    void shouldUseWebpConversion_withTextPlain_returnsFalse() {
        assertFalse(props.shouldUseWebpConversion("text/plain"));
    }

    @Test
    void shouldUseWebpConversion_withUnsupported_returnsFalse() {
        assertFalse(props.shouldUseWebpConversion("application/zip"));
    }

    // --- Default sub-objects ---

    @Test
    void defaultDimensions_are100x100() {
        assertEquals(100, props.getDimensions().getWidth());
        assertEquals(100, props.getDimensions().getHeight());
    }

    @Test
    void defaultRedisChannel_isCorrect() {
        assertEquals("openfilz:thumbnails", props.getRedis().getChannel());
    }

    @Test
    void defaultStorageUseMainStorage_isTrue() {
        assertTrue(props.getStorage().isUseMainStorage());
    }

    @Test
    void defaultGotenberg_hasCorrectDefaults() {
        assertEquals("http://gotenberg:3000", props.getGotenberg().getUrl());
        assertEquals(60, props.getGotenberg().getTimeoutSeconds());
        assertEquals(5, props.getGotenberg().getRetry().getMaxAttempts());
        assertEquals(1000, props.getGotenberg().getRetry().getBackoffInitialDelayMs());
        assertEquals(10000, props.getGotenberg().getRetry().getBackoffMaxDelayMs());
    }
}
