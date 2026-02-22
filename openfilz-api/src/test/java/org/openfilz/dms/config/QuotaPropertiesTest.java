package org.openfilz.dms.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class QuotaPropertiesTest {

    @Test
    void validate_withDefaultValues_noException() {
        QuotaProperties props = new QuotaProperties();
        assertDoesNotThrow(props::validate);
    }

    @Test
    void validate_withNegativeFileUpload_throwsException() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(-1);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void validate_withNegativeUser_throwsException() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(-1);
        assertThrows(IllegalArgumentException.class, props::validate);
    }

    @Test
    void validate_withPositiveValues_noException() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(100);
        props.setUser(500);
        assertDoesNotThrow(props::validate);
    }

    @Test
    void getFileUploadQuotaInBytes_whenZero_returnsNull() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(0);
        assertNull(props.getFileUploadQuotaInBytes());
    }

    @Test
    void getFileUploadQuotaInBytes_whenNull_returnsNull() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(null);
        assertNull(props.getFileUploadQuotaInBytes());
    }

    @Test
    void getFileUploadQuotaInBytes_whenPositive_returnsBytesValue() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(10);
        assertEquals(10L * 1024 * 1024, props.getFileUploadQuotaInBytes());
    }

    @Test
    void getUserQuotaInBytes_whenZero_returnsNull() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(0);
        assertNull(props.getUserQuotaInBytes());
    }

    @Test
    void getUserQuotaInBytes_whenNull_returnsNull() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(null);
        assertNull(props.getUserQuotaInBytes());
    }

    @Test
    void getUserQuotaInBytes_whenPositive_returnsBytesValue() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(500);
        assertEquals(500L * 1024 * 1024, props.getUserQuotaInBytes());
    }

    @Test
    void isFileUploadQuotaEnabled_whenZero_returnsFalse() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(0);
        assertFalse(props.isFileUploadQuotaEnabled());
    }

    @Test
    void isFileUploadQuotaEnabled_whenNull_returnsFalse() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(null);
        assertFalse(props.isFileUploadQuotaEnabled());
    }

    @Test
    void isFileUploadQuotaEnabled_whenPositive_returnsTrue() {
        QuotaProperties props = new QuotaProperties();
        props.setFileUpload(100);
        assertTrue(props.isFileUploadQuotaEnabled());
    }

    @Test
    void isUserQuotaEnabled_whenZero_returnsFalse() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(0);
        assertFalse(props.isUserQuotaEnabled());
    }

    @Test
    void isUserQuotaEnabled_whenNull_returnsFalse() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(null);
        assertFalse(props.isUserQuotaEnabled());
    }

    @Test
    void isUserQuotaEnabled_whenPositive_returnsTrue() {
        QuotaProperties props = new QuotaProperties();
        props.setUser(200);
        assertTrue(props.isUserQuotaEnabled());
    }
}
