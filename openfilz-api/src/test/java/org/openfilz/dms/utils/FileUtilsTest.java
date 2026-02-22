package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.enums.DocumentType;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileUtilsTest {

    // --- removeFileExtension ---

    @Test
    void removeFileExtension_withExtension_removesIt() {
        assertEquals("document", FileUtils.removeFileExtension("document.pdf"));
    }

    @Test
    void removeFileExtension_withMultipleDots_removesLast() {
        assertEquals("archive.tar", FileUtils.removeFileExtension("archive.tar.gz"));
    }

    @Test
    void removeFileExtension_withNoExtension_returnsOriginal() {
        assertEquals("README", FileUtils.removeFileExtension("README"));
    }

    @Test
    void removeFileExtension_withDotAtStart_returnsOriginal() {
        assertEquals(".gitignore", FileUtils.removeFileExtension(".gitignore"));
    }

    // --- getDocumentExtension ---

    @Test
    void getDocumentExtension_withFolder_returnsNull() {
        assertNull(FileUtils.getDocumentExtension(DocumentType.FOLDER, "myFolder"));
    }

    @Test
    void getDocumentExtension_withFile_returnsExtension() {
        assertEquals("pdf", FileUtils.getDocumentExtension(DocumentType.FILE, "document.pdf"));
    }

    @Test
    void getDocumentExtension_withFileNoExtension_returnsEmptyString() {
        assertEquals("", FileUtils.getDocumentExtension(DocumentType.FILE, "Makefile"));
    }

    @Test
    void getDocumentExtension_withFileDotAtEnd_returnsEmptyString() {
        assertEquals("", FileUtils.getDocumentExtension(DocumentType.FILE, "file."));
    }

    // --- getMetadataWithChecksum ---

    @Test
    void getMetadataWithChecksum_withNullMetadata_createsNewMapWithChecksum() {
        Map<String, Object> result = FileUtils.getMetadataWithChecksum(null, "abc123");

        assertEquals(1, result.size());
        assertEquals("abc123", result.get("sha256"));
    }

    @Test
    void getMetadataWithChecksum_withExistingMetadata_addsChecksum() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        Map<String, Object> result = FileUtils.getMetadataWithChecksum(metadata, "def456");

        assertEquals(2, result.size());
        assertEquals("value1", result.get("key1"));
        assertEquals("def456", result.get("sha256"));
    }

    @Test
    void getMetadataWithChecksum_withExistingChecksum_overwritesIt() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("sha256", "old-hash");
        metadata.put("other", "value");

        Map<String, Object> result = FileUtils.getMetadataWithChecksum(metadata, "new-hash");

        assertEquals(2, result.size());
        assertEquals("new-hash", result.get("sha256"));
    }

    @Test
    void getMetadataWithChecksum_doesNotModifyOriginal() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("key1", "value1");

        FileUtils.getMetadataWithChecksum(metadata, "hash");

        // Original should not be modified
        assertEquals(1, metadata.size());
        assertFalse(metadata.containsKey("sha256"));
    }

    // --- humanReadableBytes ---

    @Test
    void humanReadableBytes_lessThan1KB() {
        assertEquals("0 B", FileUtils.humanReadableBytes(0));
        assertEquals("500 B", FileUtils.humanReadableBytes(500));
        assertEquals("1023 B", FileUtils.humanReadableBytes(1023));
    }

    @Test
    void humanReadableBytes_kilobytes() {
        String result = FileUtils.humanReadableBytes(1024);
        assertTrue(result.contains("kB"));
        assertTrue(result.startsWith("1"));
    }

    @Test
    void humanReadableBytes_megabytes() {
        String result1MB = FileUtils.humanReadableBytes(1024 * 1024);
        assertTrue(result1MB.contains("MB"));
        assertTrue(result1MB.startsWith("1"));

        String result100MB = FileUtils.humanReadableBytes(100L * 1024 * 1024);
        assertTrue(result100MB.contains("MB"));
        assertTrue(result100MB.startsWith("100"));
    }

    @Test
    void humanReadableBytes_gigabytes() {
        String result = FileUtils.humanReadableBytes(1024L * 1024 * 1024);
        assertTrue(result.contains("GB"));
        assertTrue(result.startsWith("1"));
    }

    @Test
    void humanReadableBytes_terabytes() {
        String result = FileUtils.humanReadableBytes(1024L * 1024 * 1024 * 1024);
        assertTrue(result.contains("TB"));
        assertTrue(result.startsWith("1"));
    }
}
