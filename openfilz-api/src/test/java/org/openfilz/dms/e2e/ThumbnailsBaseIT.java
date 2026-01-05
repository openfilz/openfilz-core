package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Base integration test class for Thumbnail feature.
 * <p>
 * Tests thumbnail generation for various file types using:
 * - ImgProxy for images (PNG, JPEG, etc.)
 * - Gotenberg + PDFBox for Office documents (DOCX, XLSX, PPTX)
 * - PDFBox for PDF documents
 * - Java 2D renderer for text files (MD, YAML, XML, CSV, JSON, etc.)
 * <p>
 * Subclasses should configure the thumbnail storage backend (FileSystem or MinIO).
 */
@Slf4j
public abstract class ThumbnailsBaseIT extends TestContainersBaseConfig {

    // Maximum wait time for async thumbnail generation (in milliseconds)
    protected static final int THUMBNAIL_GENERATION_TIMEOUT_MS = 30000;
    // Polling interval for thumbnail availability check (in milliseconds)
    protected static final int THUMBNAIL_POLL_INTERVAL_MS = 500;

    // Fixed port for test server - needed for ImgProxy to call back
    protected static final int TEST_SERVER_PORT = 18081;

    // ==========================================
    // TestContainers for ImgProxy and Gotenberg
    // ==========================================

    @Container
    static GenericContainer<?> imgproxy = new GenericContainer<>(DockerImageName.parse("darthsim/imgproxy:latest"))
            .withExposedPorts(8080)
            .withEnv("IMGPROXY_BIND", ":8080")
            .withEnv("IMGPROXY_ENABLE_PDF_PAGES", "true")
            .withEnv("IMGPROXY_PDF_PAGES", "true")
            .withEnv("IMGPROXY_ENABLE_SVG_SANITIZATION", "false")
            .withEnv("IMGPROXY_SVG_FIX_UNSUPPORTED", "true")
            .withEnv("IMGPROXY_PREFERRED_FORMATS", "webp")
            .withEnv("IMGPROXY_SKIP_UNSUPPORTED_FORMATS", "true")
            .withEnv("IMGPROXY_ALLOW_INSECURE_SOURCE_URL", "true")
            .withEnv("IMGPROXY_ALLOW_LOOPBACK_SOURCE_ADDRESSES", "true")
            .withEnv("IMGPROXY_ALLOW_PRIVATE_SOURCE_ADDRESSES", "true")
            .withEnv("IMGPROXY_ALLOW_ORIGIN", "*")
            .withEnv("IMGPROXY_MAX_SRC_RESOLUTION", "50")
            .withEnv("IMGPROXY_MAX_SRC_FILE_SIZE", "52428800")
            .withEnv("IMGPROXY_CONCURRENCY", "10")
            .withEnv("IMGPROXY_MAX_CLIENTS", "200")
            .withEnv("IMGPROXY_LOG_LEVEL", "debug")
            .waitingFor(Wait.forHttp("/health").forPort(8080).forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(60));

    @Container
    static GenericContainer<?> gotenberg = new GenericContainer<>(DockerImageName.parse("gotenberg/gotenberg:8"))
            .withExposedPorts(3000)
            .withCommand("gotenberg",
                    "--api-timeout=60s",
                    "--libreoffice-restart-after=100",
                    "--libreoffice-auto-start=true",
                    "--log-level=debug")
            .waitingFor(Wait.forHttp("/health").forPort(3000).forStatusCode(200))
            .withStartupTimeout(Duration.ofSeconds(120));

    public ThumbnailsBaseIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @BeforeAll
    static void logContainerInfo() {
        log.info("ImgProxy running at: http://{}:{}", imgproxy.getHost(), imgproxy.getMappedPort(8080));
        log.info("Gotenberg running at: http://{}:{}", gotenberg.getHost(), gotenberg.getMappedPort(3000));
    }

    /**
     * Configure common properties for thumbnail generation.
     * Subclasses should call this and add storage-specific properties.
     */
    @DynamicPropertySource
    static void configureThumbnailProperties(DynamicPropertyRegistry registry) {
        // Enable thumbnails
        registry.add("openfilz.thumbnail.active", () -> "true");

        // Configure ImgProxy URL
        registry.add("openfilz.thumbnail.imgproxy.url", () ->
                String.format("http://%s:%d", imgproxy.getHost(), imgproxy.getMappedPort(8080)));

        // Configure Gotenberg URL
        registry.add("openfilz.thumbnail.gotenberg.url", () ->
                String.format("http://%s:%d", gotenberg.getHost(), gotenberg.getMappedPort(3000)));

        // Configure ImgProxy secret for token signing
        registry.add("openfilz.thumbnail.imgproxy.secret", () -> "test-secret-for-thumbnails");

        // Disable authentication for tests
        registry.add("openfilz.security.no-auth", () -> "true");

        // Configure fixed server port (for ImgProxy to call back)
        registry.add("server.port", () -> String.valueOf(TEST_SERVER_PORT));

        // Configure API internal base URL for ImgProxy callback
        // ImgProxy needs to reach our API - use host.docker.internal for Docker containers
        registry.add("openfilz.common.api-internal-base-url", () ->
                "http://host.docker.internal:" + TEST_SERVER_PORT);
    }

    // ==========================================
    // Helper Methods
    // ==========================================

    /**
     * Uploads a file and returns the upload response.
     */
    protected UploadResponse uploadFile(String resourcePath) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource(resourcePath));

        return webTestClient.post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult()
                .getResponseBody();
    }

    /**
     * Waits for thumbnail generation to complete and returns the thumbnail bytes.
     * Polls the thumbnail endpoint until the thumbnail is available or timeout.
     */
    protected byte[] waitForThumbnail(UUID documentId) throws InterruptedException {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < THUMBNAIL_GENERATION_TIMEOUT_MS) {
            WebTestClient.ResponseSpec response = webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/thumbnails/img/{documentId}", documentId)
                    .exchange();

            // Check if thumbnail is ready
            var result = response.returnResult(byte[].class);
            if (result.getStatus().is2xxSuccessful()) {
                byte[] thumbnailBytes = result.getResponseBody().blockFirst();
                if (thumbnailBytes != null && thumbnailBytes.length > 0) {
                    log.info("Thumbnail ready for document {} after {}ms",
                            documentId, System.currentTimeMillis() - startTime);
                    return thumbnailBytes;
                }
            }

            // Wait before next poll
            TimeUnit.MILLISECONDS.sleep(THUMBNAIL_POLL_INTERVAL_MS);
        }

        fail("Thumbnail generation timed out for document: " + documentId);
        return null;
    }

    /**
     * Verifies that the thumbnail is a valid PNG image.
     */
    protected void assertValidPngThumbnail(byte[] thumbnailBytes) {
        assertNotNull(thumbnailBytes, "Thumbnail bytes should not be null");
        assertTrue(thumbnailBytes.length > 0, "Thumbnail should have content");

        // Check PNG magic bytes
        assertTrue(thumbnailBytes.length >= 8, "Thumbnail too small for PNG");
        assertEquals((byte) 0x89, thumbnailBytes[0], "Invalid PNG signature byte 0");
        assertEquals((byte) 0x50, thumbnailBytes[1], "Invalid PNG signature byte 1 (P)");
        assertEquals((byte) 0x4E, thumbnailBytes[2], "Invalid PNG signature byte 2 (N)");
        assertEquals((byte) 0x47, thumbnailBytes[3], "Invalid PNG signature byte 3 (G)");
    }

    /**
     * Verifies that the thumbnail is a valid WebP image (used by ImgProxy).
     */
    protected void assertValidWebPThumbnail(byte[] thumbnailBytes) {
        assertNotNull(thumbnailBytes, "Thumbnail bytes should not be null");
        assertTrue(thumbnailBytes.length > 0, "Thumbnail should have content");

        // Check RIFF header and WebP signature
        assertTrue(thumbnailBytes.length >= 12, "Thumbnail too small for WebP");
        assertEquals((byte) 0x52, thumbnailBytes[0], "Invalid RIFF signature byte 0 (R)");
        assertEquals((byte) 0x49, thumbnailBytes[1], "Invalid RIFF signature byte 1 (I)");
        assertEquals((byte) 0x46, thumbnailBytes[2], "Invalid RIFF signature byte 2 (F)");
        assertEquals((byte) 0x46, thumbnailBytes[3], "Invalid RIFF signature byte 3 (F)");
        // Bytes 4-7 are file size
        assertEquals((byte) 0x57, thumbnailBytes[8], "Invalid WebP signature byte 8 (W)");
        assertEquals((byte) 0x45, thumbnailBytes[9], "Invalid WebP signature byte 9 (E)");
        assertEquals((byte) 0x42, thumbnailBytes[10], "Invalid WebP signature byte 10 (B)");
        assertEquals((byte) 0x50, thumbnailBytes[11], "Invalid WebP signature byte 11 (P)");
    }

    /**
     * Verifies that the thumbnail is a valid image (either PNG or WebP).
     */
    protected void assertValidImageThumbnail(byte[] thumbnailBytes) {
        assertNotNull(thumbnailBytes, "Thumbnail bytes should not be null");
        assertTrue(thumbnailBytes.length > 0, "Thumbnail should have content");
        assertTrue(thumbnailBytes.length >= 8, "Thumbnail too small to be valid image");

        // Check if PNG or WebP
        boolean isPng = thumbnailBytes[0] == (byte) 0x89 && thumbnailBytes[1] == (byte) 0x50;
        boolean isWebP = thumbnailBytes[0] == (byte) 0x52 && thumbnailBytes[1] == (byte) 0x49;

        assertTrue(isPng || isWebP, "Thumbnail should be either PNG or WebP format");

        if (isPng) {
            assertValidPngThumbnail(thumbnailBytes);
        } else {
            assertValidWebPThumbnail(thumbnailBytes);
        }
    }

    // ==========================================
    // Image Thumbnail Tests (ImgProxy)
    // ==========================================

    @Test
    @DisplayName("Should generate thumbnail for PNG image using ImgProxy")
    void shouldGenerateThumbnailForPngImage() throws InterruptedException {
        // Upload PNG image
        UploadResponse uploadResponse = uploadFile("test-image.png");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded PNG image with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid (WebP from ImgProxy)
        assertValidImageThumbnail(thumbnail);
        log.info("PNG thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for JPEG image using ImgProxy")
    void shouldGenerateThumbnailForJpegImage() throws InterruptedException {
        // Upload JPEG image
        UploadResponse uploadResponse = uploadFile("test-image.jpg");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded JPEG image with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid (WebP from ImgProxy)
        assertValidImageThumbnail(thumbnail);
        log.info("JPEG thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    // ==========================================
    // PDF Thumbnail Tests (PDFBox)
    // ==========================================

    @Test
    @DisplayName("Should generate thumbnail for PDF document using PDFBox")
    void shouldGenerateThumbnailForPdfDocument() throws InterruptedException {
        // Upload PDF document
        UploadResponse uploadResponse = uploadFile("pdf-example.pdf");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded PDF document with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG (PDFBox outputs PNG)
        assertValidPngThumbnail(thumbnail);
        log.info("PDF thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    // ==========================================
    // Office Document Thumbnail Tests (Gotenberg + PDFBox)
    // ==========================================

    @Test
    @DisplayName("Should generate thumbnail for DOCX document using Gotenberg")
    void shouldGenerateThumbnailForDocxDocument() throws InterruptedException {
        // Upload DOCX document
        UploadResponse uploadResponse = uploadFile("test-document.docx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded DOCX document with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation (may take longer due to Gotenberg conversion)
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG (Gotenberg -> PDF -> PDFBox -> PNG)
        assertValidPngThumbnail(thumbnail);
        log.info("DOCX thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for XLSX spreadsheet using Gotenberg")
    void shouldGenerateThumbnailForXlsxDocument() throws InterruptedException {
        // Upload XLSX spreadsheet
        UploadResponse uploadResponse = uploadFile("test-spreadsheet.xlsx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded XLSX spreadsheet with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("XLSX thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for PPTX presentation using Gotenberg")
    void shouldGenerateThumbnailForPptxDocument() throws InterruptedException {
        // Upload PPTX presentation
        UploadResponse uploadResponse = uploadFile("test-presentation.pptx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded PPTX presentation with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("PPTX thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    // ==========================================
    // Text File Thumbnail Tests (Java 2D Renderer)
    // ==========================================

    @Test
    @DisplayName("Should generate thumbnail for plain text file using Java 2D renderer")
    void shouldGenerateThumbnailForPlainTextFile() throws InterruptedException {
        // Upload plain text file
        UploadResponse uploadResponse = uploadFile("test.txt");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded text file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG (Java 2D outputs PNG)
        assertValidPngThumbnail(thumbnail);
        log.info("Text file thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for Markdown file using Java 2D renderer")
    void shouldGenerateThumbnailForMarkdownFile() throws InterruptedException {
        // Upload Markdown file
        UploadResponse uploadResponse = uploadFile("test-markdown.md");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded Markdown file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("Markdown thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for YAML config file using Java 2D renderer")
    void shouldGenerateThumbnailForYamlFile() throws InterruptedException {
        // Upload YAML file
        UploadResponse uploadResponse = uploadFile("test-config.yaml");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded YAML file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("YAML thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for XML file using Java 2D renderer")
    void shouldGenerateThumbnailForXmlFile() throws InterruptedException {
        // Upload XML file
        UploadResponse uploadResponse = uploadFile("test-data.xml");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded XML file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("XML thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for CSV file using Java 2D renderer")
    void shouldGenerateThumbnailForCsvFile() throws InterruptedException {
        // Upload CSV file
        UploadResponse uploadResponse = uploadFile("test-data.csv");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded CSV file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("CSV thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for JSON file using Java 2D renderer")
    void shouldGenerateThumbnailForJsonFile() throws InterruptedException {
        // Upload JSON file
        UploadResponse uploadResponse = uploadFile("test-code.json");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded JSON file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("JSON thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for SQL file using Java 2D renderer")
    void shouldGenerateThumbnailForSqlFile() throws InterruptedException {
        // Upload SQL file
        UploadResponse uploadResponse = uploadFile("test_file_1.sql");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded SQL file with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid PNG
        assertValidPngThumbnail(thumbnail);
        log.info("SQL thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    // ==========================================
    // Error Handling Tests
    // ==========================================

    @Test
    @DisplayName("Should return 404 for non-existent document thumbnail")
    void shouldReturn404ForNonExistentThumbnail() {
        UUID nonExistentId = UUID.randomUUID();

        webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/thumbnails/img/{documentId}", nonExistentId)
                .exchange()
                .expectStatus().isNotFound();

        log.info("Correctly returned 404 for non-existent document: {}", nonExistentId);
    }
}
