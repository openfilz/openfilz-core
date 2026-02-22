package org.openfilz.dms.exception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FileSizeExceededExceptionTest {

    @Test
    void constructorWithFilename_containsFileInfo() {
        FileSizeExceededException ex = new FileSizeExceededException("report.pdf", 20 * 1024 * 1024, 10 * 1024 * 1024);
        assertTrue(ex.getMessage().contains("report.pdf"));
        assertTrue(ex.getMessage().contains("20 MB"));
        assertTrue(ex.getMessage().contains("10 MB"));
    }

    @Test
    void constructorWithoutFilename_containsSizeInfo() {
        FileSizeExceededException ex = new FileSizeExceededException(50 * 1024 * 1024, 25 * 1024 * 1024);
        assertTrue(ex.getMessage().contains("50 MB"));
        assertTrue(ex.getMessage().contains("25 MB"));
        assertFalse(ex.getMessage().contains("null"));
    }

    @Test
    void getError_returnsFileSizeExceeded() {
        FileSizeExceededException ex = new FileSizeExceededException("file.txt", 1024, 512);
        assertEquals("FileSizeExceeded", ex.getError());
    }
}
