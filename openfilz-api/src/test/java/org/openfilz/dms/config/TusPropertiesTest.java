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

    @Test
    void getMaxUploadSizeInMB_returnsCorrectValue() {
        TusProperties props = new TusProperties();
        assertEquals(10240, props.getMaxUploadSizeInMB()); // 10GB = 10240 MB
    }

    @Test
    void getChunkSizeInMB_returnsCorrectValue() {
        TusProperties props = new TusProperties();
        assertEquals(50, props.getChunkSizeInMB()); // 50MB
    }

    @Test
    void getUploadExpirationPeriodInHours_returnsCorrectValue() {
        TusProperties props = new TusProperties();
        assertEquals(24, props.getUploadExpirationPeriodInHours()); // 24 hours
    }

    @Test
    void getMaxUploadSizeInMB_withCustomValue() {
        TusProperties props = new TusProperties();
        props.setMaxUploadSize(1024L * 1024 * 512); // 512 MB
        assertEquals(512, props.getMaxUploadSizeInMB());
    }

    @Test
    void getChunkSizeInMB_withCustomValue() {
        TusProperties props = new TusProperties();
        props.setChunkSize(1024L * 1024 * 25); // 25 MB
        assertEquals(25, props.getChunkSizeInMB());
    }

    @Test
    void getUploadExpirationPeriodInHours_withCustomValue() {
        TusProperties props = new TusProperties();
        props.setUploadExpirationPeriod(3600000L * 48); // 48 hours
        assertEquals(48, props.getUploadExpirationPeriodInHours());
    }
}
