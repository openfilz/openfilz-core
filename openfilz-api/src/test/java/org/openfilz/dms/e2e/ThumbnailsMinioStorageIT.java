package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * End-to-end integration tests for Thumbnail generation using MinIO/S3 storage.
 * <p>
 * This test class verifies thumbnail generation for all supported file types:
 * - Images (PNG, JPEG) via ImgProxy
 * - PDF documents via PDFBox
 * - Office documents (DOCX, XLSX, PPTX) via Gotenberg + PDFBox
 * - Text files (TXT, MD, YAML, XML, CSV, JSON, SQL) via Java 2D renderer
 * <p>
 * Uses TestContainers to run:
 * - PostgreSQL for database
 * - MinIO for document and thumbnail storage
 * - ImgProxy for image thumbnail generation
 * - Gotenberg for Office document conversion to PDF
 * <p>
 * Thumbnail storage backend: MinIO/S3
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
@DisplayName("Thumbnails E2E Tests - MinIO Storage")
public class ThumbnailsMinioStorageIT extends ThumbnailsBaseIT {

    @Container
    static MinIOContainer minio = new MinIOContainer("minio/minio:latest");

    public ThumbnailsMinioStorageIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void configureMinioStorageProperties(DynamicPropertyRegistry registry) {
        // Call parent configuration
        ThumbnailsBaseIT.configureThumbnailProperties(registry);

        // Configure MinIO storage for documents
        registry.add("storage.type", () -> "minio");
        registry.add("storage.minio.endpoint", minio::getS3URL);
        registry.add("storage.minio.access-key", minio::getUserName);
        registry.add("storage.minio.secret-key", minio::getPassword);
        registry.add("storage.minio.bucket-name", () -> "dms-documents");

        // Configure thumbnail storage to use main storage (MinIO)
        registry.add("openfilz.thumbnail.storage.use-main-storage", () -> "true");
        registry.add("openfilz.thumbnail.storage.type", () -> "minio");
        registry.add("openfilz.thumbnail.storage.minio.endpoint", minio::getS3URL);
        registry.add("openfilz.thumbnail.storage.minio.access-key", minio::getUserName);
        registry.add("openfilz.thumbnail.storage.minio.secret-key", minio::getPassword);
        registry.add("openfilz.thumbnail.storage.minio.bucket-name", () -> "dms-thumbnails");

        // Configure thumbnail dimensions
        registry.add("openfilz.thumbnail.dimensions.width", () -> "160");
        registry.add("openfilz.thumbnail.dimensions.height", () -> "160");

        log.info("Configured MinIO storage for documents at: {}", minio.getS3URL());
        log.info("Document bucket: dms-documents, Thumbnail bucket: dms-thumbnails");
    }

    @Test
    void shouldGenerateThumbnailForDocxDocument() throws InterruptedException{
        super.shouldGenerateThumbnailForDocxDocument();
    }
}
