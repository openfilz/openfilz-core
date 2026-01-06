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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.CopyResponse;

/**
 * Base integration test class for Thumbnail feature.
 * <p>
 * Tests thumbnail generation for various file types using:
 * - Custom WebP conversion for images (PNG, JPEG, etc.)
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
    // TestContainer for Gotenberg
    // ==========================================

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

        // Configure Gotenberg URL
        registry.add("openfilz.thumbnail.gotenberg.url", () ->
                String.format("http://%s:%d", gotenberg.getHost(), gotenberg.getMappedPort(3000)));

        // Disable authentication for tests
        registry.add("openfilz.security.no-auth", () -> "true");

        // Configure fixed server port
        registry.add("server.port", () -> String.valueOf(TEST_SERVER_PORT));

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
                byte[] thumbnailBytes =
                        response.returnResult(byte[].class)
                                .getResponseBody()
                                .reduce(new ByteArrayOutputStream(), (out, chunk) -> {
                                    try {
                                        out.write(chunk);
                                        return out;
                                    } catch (IOException e) {
                                        throw new UncheckedIOException(e);
                                    }
                                })
                                .map(ByteArrayOutputStream::toByteArray)
                                .block();
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
     * Verifies that the thumbnail is a valid WebP image (used by custom WebP converter).
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
    // Image Thumbnail Tests (Custom WebP conversion)
    // ==========================================

    @Test
    @DisplayName("Should generate thumbnail for PNG image using Custom WebP conversion")
    void shouldGenerateThumbnailForPngImage() throws InterruptedException {
        // Upload PNG image
        UploadResponse uploadResponse = uploadFile("test-image.png");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded PNG image with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid (WebP)
        assertValidImageThumbnail(thumbnail);
        log.info("PNG thumbnail generated successfully, size: {} bytes", thumbnail.length);
    }

    @Test
    @DisplayName("Should generate thumbnail for JPEG image using Custom WebP conversion")
    void shouldGenerateThumbnailForJpegImage() throws InterruptedException {
        // Upload JPEG image
        UploadResponse uploadResponse = uploadFile("test-image.jpg");
        assertNotNull(uploadResponse, "Upload response should not be null");
        assertNotNull(uploadResponse.id(), "Document ID should not be null");

        log.info("Uploaded JPEG image with ID: {}", uploadResponse.id());

        // Wait for thumbnail generation
        byte[] thumbnail = waitForThumbnail(uploadResponse.id());

        // Verify thumbnail is valid (WebP )
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

    // ==========================================
    // Helper Methods for Copy/Delete Tests
    // ==========================================

    /**
     * Copies a file to the root folder and returns the copy response.
     */
    protected CopyResponse copyFile(UUID documentId) {
        CopyRequest request = new CopyRequest(List.of(documentId), null, true);

        List<CopyResponse> responses = webTestClient.post()
                .uri(RestApiVersion.API_PREFIX + "/files/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(CopyResponse.class)
                .returnResult()
                .getResponseBody();

        assertNotNull(responses, "Copy response should not be null");
        assertFalse(responses.isEmpty(), "Copy response should not be empty");
        return responses.getFirst();
    }

    /**
     * Soft-deletes a file (moves to recycle bin).
     * Requires openfilz.soft-delete.active=true
     */
    protected void softDeleteFile(UUID documentId) {
        DeleteRequest request = new DeleteRequest(List.of(documentId));

        webTestClient.delete()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/files")
                        .build())
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .exchange()
                .expectStatus().isNoContent();

        log.info("Soft-deleted file: {}", documentId);
    }

    /**
     * Soft-deletes files using the FileController DELETE endpoint.
     * This method sends a DELETE request with a JSON body.
     */
    protected void softDeleteFiles(List<UUID> documentIds) {
        DeleteRequest request = new DeleteRequest(documentIds);

        webTestClient.method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/files")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isNoContent();

        log.info("Soft-deleted files: {}", documentIds);
    }

    /**
     * Empties the recycle bin (permanently deletes all items).
     * Requires openfilz.soft-delete.active=true
     */
    protected void emptyRecycleBin() {
        webTestClient.delete()
                .uri(RestApiVersion.API_PREFIX + "/recycle-bin/empty")
                .exchange()
                .expectStatus().isNoContent();

        log.info("Emptied recycle bin");
    }

    /**
     * Checks if a thumbnail exists for a document (returns immediately, no waiting).
     */
    protected boolean thumbnailExists(UUID documentId) {
        var result = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/thumbnails/img/{documentId}", documentId)
                .exchange()
                .returnResult(byte[].class);

        return result.getStatus().is2xxSuccessful();
    }

    /**
     * Gets thumbnail bytes if they exist, returns null otherwise.
     */
    protected byte[] getThumbnailIfExists(UUID documentId) {
        var result = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/thumbnails/img/{documentId}", documentId)
                .exchange()
                .returnResult(byte[].class);

        if (result.getStatus().is2xxSuccessful()) {
            return result.getResponseBody().blockFirst();
        }
        return null;
    }

    /**
     * Asserts that thumbnail does NOT exist (404).
     */
    protected void assertThumbnailNotFound(UUID documentId) {
        webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/thumbnails/img/{documentId}", documentId)
                .exchange()
                .expectStatus().isNotFound();
    }

    /**
     * Waits a short time for async operations to complete.
     */
    protected void waitForAsyncOperation(long millis) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(millis);
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - PNG (Custom WebP conversion)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: PNG image - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForPngImage() throws InterruptedException {
        // 1. Upload PNG image
        UploadResponse uploadResponse = uploadFile("test-image.png");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded PNG image with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidImageThumbnail(originalThumbnail);
        log.info("Original PNG thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied PNG image to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied (async operation)
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidImageThumbnail(copiedThumbnail);
        log.info("Copied PNG thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails - they should be equal
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied thumbnails should be identical");
        log.info("PNG thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists (soft-delete doesn't delete thumbnails)
        byte[] thumbnailAfterSoftDelete = getThumbnailIfExists(originalId);
        assertNotNull(thumbnailAfterSoftDelete, "Thumbnail should still exist after soft-delete");
        assertArrayEquals(originalThumbnail, thumbnailAfterSoftDelete,
                "Thumbnail should be unchanged after soft-delete");
        log.info("PNG thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin (permanent delete)
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("PNG thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        byte[] copiedThumbnailAfterDelete = getThumbnailIfExists(copyId);
        assertNotNull(copiedThumbnailAfterDelete, "Copied thumbnail should still exist");
        log.info("Copied PNG thumbnail still exists after original deleted");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - JPEG (Custom WebP conversion)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: JPEG image - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForJpegImage() throws InterruptedException {
        // 1. Upload JPEG image
        UploadResponse uploadResponse = uploadFile("test-image.jpg");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded JPEG image with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidImageThumbnail(originalThumbnail);
        log.info("Original JPEG thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied JPEG image to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidImageThumbnail(copiedThumbnail);
        log.info("Copied JPEG thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied JPEG thumbnails should be identical");
        log.info("JPEG thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        byte[] thumbnailAfterSoftDelete = getThumbnailIfExists(originalId);
        assertNotNull(thumbnailAfterSoftDelete, "JPEG thumbnail should still exist after soft-delete");
        log.info("JPEG thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("JPEG thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied JPEG thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - PDF (PDFBox)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: PDF document - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForPdfDocument() throws InterruptedException {
        // 1. Upload PDF document
        UploadResponse uploadResponse = uploadFile("pdf-example.pdf");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded PDF document with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original PDF thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied PDF document to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);
        log.info("Copied PDF thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied PDF thumbnails should be identical");
        log.info("PDF thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        byte[] thumbnailAfterSoftDelete = getThumbnailIfExists(originalId);
        assertNotNull(thumbnailAfterSoftDelete, "PDF thumbnail should still exist after soft-delete");
        log.info("PDF thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("PDF thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied PDF thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - DOCX (Gotenberg + PDFBox)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: DOCX document - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForDocxDocument() throws InterruptedException {
        // 1. Upload DOCX document
        UploadResponse uploadResponse = uploadFile("test-document.docx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded DOCX document with ID: {}", originalId);

        // 2. Wait for thumbnail generation (Gotenberg conversion may take longer)
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original DOCX thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied DOCX document to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);
        log.info("Copied DOCX thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied DOCX thumbnails should be identical");
        log.info("DOCX thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        byte[] thumbnailAfterSoftDelete = getThumbnailIfExists(originalId);
        assertNotNull(thumbnailAfterSoftDelete, "DOCX thumbnail should still exist after soft-delete");
        log.info("DOCX thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("DOCX thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied DOCX thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - XLSX (Gotenberg + PDFBox)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: XLSX spreadsheet - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForXlsxDocument() throws InterruptedException {
        // 1. Upload XLSX spreadsheet
        UploadResponse uploadResponse = uploadFile("test-spreadsheet.xlsx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded XLSX spreadsheet with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original XLSX thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied XLSX spreadsheet to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);
        log.info("Copied XLSX thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied XLSX thumbnails should be identical");
        log.info("XLSX thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        assertNotNull(getThumbnailIfExists(originalId), "XLSX thumbnail should still exist after soft-delete");
        log.info("XLSX thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("XLSX thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied XLSX thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - PPTX (Gotenberg + PDFBox)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: PPTX presentation - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForPptxDocument() throws InterruptedException {
        // 1. Upload PPTX presentation
        UploadResponse uploadResponse = uploadFile("test-presentation.pptx");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded PPTX presentation with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original PPTX thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied PPTX presentation to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);
        log.info("Copied PPTX thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied PPTX thumbnails should be identical");
        log.info("PPTX thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        assertNotNull(getThumbnailIfExists(originalId), "PPTX thumbnail should still exist after soft-delete");
        log.info("PPTX thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("PPTX thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied PPTX thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Thumbnail Tests - Text files (Java 2D)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: TXT file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForTxtFile() throws InterruptedException {
        // 1. Upload TXT file
        UploadResponse uploadResponse = uploadFile("test.txt");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded TXT file with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original TXT thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied TXT file to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);
        log.info("Copied TXT thumbnail size: {} bytes", copiedThumbnail.length);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied TXT thumbnails should be identical");
        log.info("TXT thumbnails are identical");

        // 6. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 7. Verify original thumbnail still exists after soft-delete
        assertNotNull(getThumbnailIfExists(originalId), "TXT thumbnail should still exist after soft-delete");
        log.info("TXT thumbnail preserved after soft-delete");

        // 8. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 9. Verify original thumbnail has been deleted
        assertThumbnailNotFound(originalId);
        log.info("TXT thumbnail deleted after permanent delete");

        // 10. Verify copied thumbnail still exists
        assertNotNull(getThumbnailIfExists(copyId), "Copied TXT thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: Markdown file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForMarkdownFile() throws InterruptedException {
        // 1. Upload Markdown file
        UploadResponse uploadResponse = uploadFile("test-markdown.md");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded Markdown file with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);
        log.info("Original Markdown thumbnail size: {} bytes", originalThumbnail.length);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied Markdown file to ID: {}", copyId);

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied Markdown thumbnails should be identical");
        log.info("Markdown thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "Markdown thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied Markdown thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: YAML file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForYamlFile() throws InterruptedException {
        // 1. Upload YAML file
        UploadResponse uploadResponse = uploadFile("test-config.yaml");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded YAML file with ID: {}", originalId);

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);
        assertValidPngThumbnail(copiedThumbnail);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied YAML thumbnails should be identical");
        log.info("YAML thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "YAML thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied YAML thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: XML file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForXmlFile() throws InterruptedException {
        // 1. Upload XML file
        UploadResponse uploadResponse = uploadFile("test-data.xml");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied XML thumbnails should be identical");
        log.info("XML thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "XML thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied XML thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: CSV file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForCsvFile() throws InterruptedException {
        // 1. Upload CSV file
        UploadResponse uploadResponse = uploadFile("test-data.csv");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied CSV thumbnails should be identical");
        log.info("CSV thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "CSV thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied CSV thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: JSON file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForJsonFile() throws InterruptedException {
        // 1. Upload JSON file
        UploadResponse uploadResponse = uploadFile("test-code.json");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied JSON thumbnails should be identical");
        log.info("JSON thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "JSON thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied JSON thumbnail should still exist");
    }

    @Test
    @DisplayName("Copy/Delete: SQL file - should copy thumbnail, preserve on soft-delete, remove on permanent delete")
    void shouldCopyAndDeleteThumbnailForSqlFile() throws InterruptedException {
        // 1. Upload SQL file
        UploadResponse uploadResponse = uploadFile("test_file_1.sql");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();

        // 2. Wait for thumbnail generation
        byte[] originalThumbnail = waitForThumbnail(originalId);
        assertValidPngThumbnail(originalThumbnail);

        // 3. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();

        // 4. Wait for thumbnail to be copied
        byte[] copiedThumbnail = waitForThumbnail(copyId);

        // 5. Compare both thumbnails
        assertArrayEquals(originalThumbnail, copiedThumbnail,
                "Original and copied SQL thumbnails should be identical");
        log.info("SQL thumbnails are identical");

        // 6-10. Soft-delete, verify, permanent delete, verify
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);
        assertNotNull(getThumbnailIfExists(originalId), "SQL thumbnail should still exist after soft-delete");

        emptyRecycleBin();
        waitForAsyncOperation(1000);
        assertThumbnailNotFound(originalId);
        assertNotNull(getThumbnailIfExists(copyId), "Copied SQL thumbnail should still exist");
    }

    // ==========================================
    // Copy and Delete Tests - File without extension (No thumbnail)
    // ==========================================

    @Test
    @DisplayName("Copy/Delete: File without extension - should NOT generate thumbnail, copy should have no thumbnail")
    void shouldNotGenerateThumbnailForFileWithoutExtension() throws InterruptedException {
        // 1. Upload file without extension
        UploadResponse uploadResponse = uploadFile("test-file-no-extension");
        assertNotNull(uploadResponse, "Upload response should not be null");
        UUID originalId = uploadResponse.id();
        log.info("Uploaded file without extension with ID: {}", originalId);

        // 2. Wait a bit to allow any potential thumbnail generation
        waitForAsyncOperation(3000);

        // 3. Verify NO thumbnail was generated (should return 404)
        assertThumbnailNotFound(originalId);
        log.info("Correctly no thumbnail generated for file without extension");

        // 4. Copy the file
        CopyResponse copyResponse = copyFile(originalId);
        UUID copyId = copyResponse.copyId();
        log.info("Copied file without extension to ID: {}", copyId);

        // 5. Wait a bit for any async operations
        waitForAsyncOperation(1000);

        // 6. Verify copy also has no thumbnail
        assertThumbnailNotFound(copyId);
        log.info("Copy also has no thumbnail (as expected)");

        // 7. Soft-delete the original file
        softDeleteFiles(List.of(originalId));
        waitForAsyncOperation(500);

        // 8. Original should still not have thumbnail (was never created)
        assertThumbnailNotFound(originalId);

        // 9. Empty the recycle bin
        emptyRecycleBin();
        waitForAsyncOperation(1000);

        // 10. Verify both still have no thumbnails
        assertThumbnailNotFound(originalId);
        assertThumbnailNotFound(copyId);
        log.info("File without extension: no thumbnails at any stage (as expected)");
    }
}
