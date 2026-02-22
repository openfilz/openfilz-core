package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TusPropertiesTest {

    @Test
    void defaultValues_areCorrect() {
        TusProperties props = new TusProperties();

        assertTrue(props.isEnabled());
        assertEquals(10737418240L, props.getMaxUploadSize());
        assertEquals(52428800L, props.getChunkSize());
        assertEquals(86400000L, props.getUploadExpirationPeriod());
        assertEquals(3600000L, props.getCleanupInterval());
    }

    @Test
    void validate_withDefaultValues_succeeds() {
        TusProperties props = new TusProperties();
        assertDoesNotThrow(props::validate);
    }

    @Test
    void validate_withZeroMaxUploadSize_throws() {
        TusProperties props = new TusProperties();
        props.setMaxUploadSize(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("max-upload-size must be > 0"));
    }

    @Test
    void validate_withNegativeMaxUploadSize_throws() {
        TusProperties props = new TusProperties();
        props.setMaxUploadSize(-1);

        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void validate_withZeroChunkSize_throws() {
        TusProperties props = new TusProperties();
        props.setChunkSize(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("chunk-size must be > 0"));
    }

    @Test
    void validate_withNegativeChunkSize_throws() {
        TusProperties props = new TusProperties();
        props.setChunkSize(-5);

        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void validate_withZeroUploadExpirationPeriod_throws() {
        TusProperties props = new TusProperties();
        props.setUploadExpirationPeriod(0);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, props::validate);
        assertTrue(ex.getMessage().contains("upload-expiration-period must be > 0"));
    }

    @Test
    void validate_withNegativeUploadExpirationPeriod_throws() {
        TusProperties props = new TusProperties();
        props.setUploadExpirationPeriod(-100);

        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void validate_whenDisabled_doesNotLogEnabled() {
        TusProperties props = new TusProperties();
        props.setEnabled(false);
        assertDoesNotThrow(props::validate);
    }

}
