package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * End-to-end integration tests for Thumbnail generation using FileSystem storage.
 * <p>
 * This test class verifies thumbnail generation for all supported file types:
 * - Images (PNG, JPEG) via ImgProxy
 * - PDF documents via PDFBox
 * - Office documents (DOCX, XLSX, PPTX) via Gotenberg + PDFBox
 * - Text files (TXT, MD, YAML, XML, CSV, JSON, SQL) via Java 2D renderer
 * <p>
 * Uses TestContainers to run:
 * - PostgreSQL for database
 * - ImgProxy for image thumbnail generation
 * - Gotenberg for Office document conversion to PDF
 * <p>
 * Thumbnail storage backend: Local FileSystem
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@DisplayName("Thumbnails E2E Tests - FileSystem Storage")
public class ThumbnailsLocalStorageIT extends ThumbnailsBaseIT {

    private static Path thumbnailStoragePath;

    static {
        try {
            // Create temporary directory for thumbnail storage
            thumbnailStoragePath = Files.createTempDirectory("thumbnails-test");
            log.info("Created thumbnail storage directory: {}", thumbnailStoragePath);
        } catch (IOException e) {
            throw new RuntimeException("Failed to create thumbnail storage directory", e);
        }
    }

    public ThumbnailsLocalStorageIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureLocalStorageProperties(DynamicPropertyRegistry registry) {
        // Call parent configuration
        ThumbnailsBaseIT.configureThumbnailProperties(registry);

        // Configure local file system storage for documents
        registry.add("storage.type", () -> "local");
        registry.add("storage.local.base-path", () -> {
            try {
                return Files.createTempDirectory("documents-test").toString();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        // Configure thumbnail storage to use main storage (local file system)
        registry.add("openfilz.thumbnail.storage.use-main-storage", () -> "true");
        registry.add("openfilz.thumbnail.storage.type", () -> "local");
        registry.add("openfilz.thumbnail.storage.local.base-path", thumbnailStoragePath::toString);

        // Configure thumbnail dimensions
        registry.add("openfilz.thumbnail.dimensions.width", () -> "160");
        registry.add("openfilz.thumbnail.dimensions.height", () -> "160");

        log.info("Configured FileSystem storage for thumbnails at: {}", thumbnailStoragePath);
    }
}
