package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ThumbnailService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpEntity;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * Implementation of ThumbnailService that uses:
 * - ImgProxy for image thumbnails (JPEG, PNG, GIF, WebP, etc.)
 * - PDFBox for PDF thumbnails
 * - Gotenberg + PDFBox for Office document thumbnails
 * - Java 2D for text file thumbnails (markdown, code, config files, etc.)
 * <p>
 * Flow for images:
 * 1. Build ImgProxy URL with source document URL
 * 2. ImgProxy fetches document from /api/v1/thumbnails/source/{id}
 * 3. ImgProxy generates thumbnail and returns it
 * 4. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for PDFs:
 * 1. Stream PDF from storage directly to PDFBox
 * 2. Use PDFBox to render first page as image
 * 3. Resize to thumbnail dimensions
 * 4. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for Office documents:
 * 1. Stream document from storage directly to Gotenberg (no byte array in memory)
 * 2. Gotenberg converts to PDF using LibreOffice
 * 3. Use PDFBox to render first page as image
 * 4. Resize to thumbnail dimensions
 * 5. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for text files:
 * 1. Read first N lines from storage (streaming, memory-efficient)
 * 2. Render text to BufferedImage using Java 2D with monospace font
 * 3. Store thumbnail via ThumbnailStorageService
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "true")
public class ThumbnailServiceImpl implements ThumbnailService {

    private final CommonProperties commonProperties;
    private final ThumbnailStorageService thumbnailStorage;
    private final ThumbnailProperties thumbnailProperties;
    private final WebClient.Builder webClientBuilder;
    private final StorageService storageService;

    private WebClient imgProxyClient;
    private WebClient gotenbergClient;

    /**
     * Lazy initialization of ImgProxy WebClient.
     */
    private WebClient getImgProxyClient() {
        if (imgProxyClient == null) {
            imgProxyClient = webClientBuilder
                .baseUrl(thumbnailProperties.getImgproxy().getUrl())
                .build();
        }
        return imgProxyClient;
    }

    /**
     * Lazy initialization of Gotenberg WebClient.
     */
    private WebClient getGotenbergClient() {
        if (gotenbergClient == null) {
            gotenbergClient = webClientBuilder
                .baseUrl(thumbnailProperties.getGotenberg().getUrl())
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB for PDF response
                .build();
        }
        return gotenbergClient;
    }

    @Override
    public Mono<Void> generateThumbnail(Document document) {
        if (!isSupported(document.getContentType())) {
            log.debug("Thumbnail generation not supported for content type: {}", document.getContentType());
            return Mono.empty();
        }

        Mono<byte[]> thumbnailBytes;

        if (thumbnailProperties.shouldUseImgProxy(document.getContentType())) {
            // Images: use ImgProxy
            log.debug("Using ImgProxy for document: {} (type: {})", document.getId(), document.getContentType());
            thumbnailBytes = buildImgProxyPath(document)
                .flatMap(this::fetchThumbnailFromImgProxy);
        } else if (thumbnailProperties.shouldUsePdfBox(document.getContentType())) {
            // PDFs: use PDFBox directly
            log.debug("Using PDFBox for document: {} (type: {})", document.getId(), document.getContentType());
            thumbnailBytes = renderPdfThumbnailFromStorage(document.getStoragePath());
        } else if (thumbnailProperties.shouldUseGotenberg(document.getContentType())) {
            // Office documents: use Gotenberg + PDFBox
            log.debug("Using Gotenberg for document: {} (type: {})", document.getId(), document.getContentType());
            thumbnailBytes = convertToPdfWithGotenbergStreaming(document.getStoragePath(), document.getName())
                .flatMap(this::renderPdfThumbnail);
        } else if (thumbnailProperties.shouldUseTextRenderer(document.getContentType())) {
            // Text files: use Java 2D text rendering
            log.debug("Using text renderer for document: {} (type: {})", document.getId(), document.getContentType());
            thumbnailBytes = renderTextThumbnailFromStorage(document.getStoragePath());
        } else {
            log.debug("No thumbnail handler for content type: {}", document.getContentType());
            return Mono.empty();
        }

        return thumbnailBytes
            .flatMap(bytes -> thumbnailStorage.saveThumbnail(document.getId(), bytes))
            .doOnSuccess(v -> log.info("Thumbnail generated successfully for document: {}", document.getId()))
            .doOnError(e -> log.error("Failed to generate thumbnail for document: {}", document.getId(), e))
            .onErrorResume(e -> {
                // Log error but don't fail the whole operation
                log.warn("Thumbnail generation failed for document {}, skipping: {}",
                    document.getId(), e.getMessage());
                return Mono.empty();
            })
            .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Renders PDF thumbnail by streaming directly from storage to PDFBox.
     * Avoids loading the entire PDF into a byte array first.
     */
    private Mono<byte[]> renderPdfThumbnailFromStorage(String storagePath) {
        return storageService.loadFile(storagePath)
            .flatMap(resource -> Mono.fromCallable(() -> {
                log.debug("Rendering PDF thumbnail with PDFBox from storage: {}", storagePath);

                try (InputStream inputStream = resource.getInputStream();
                     RandomAccessReadBuffer readBuffer = new RandomAccessReadBuffer(inputStream);
                     PDDocument pdfDocument = Loader.loadPDF(readBuffer)) {

                    if (pdfDocument.getNumberOfPages() == 0) {
                        throw new IOException("PDF has no pages");
                    }

                    PDFRenderer renderer = new PDFRenderer(pdfDocument);
                    // Render at 72 DPI for reasonable quality/speed trade-off
                    BufferedImage pageImage = renderer.renderImageWithDPI(0, 72);

                    // Resize to thumbnail dimensions while maintaining aspect ratio
                    BufferedImage thumbnail = resizeImage(pageImage,
                        thumbnailProperties.getDimensions().getWidth(),
                        thumbnailProperties.getDimensions().getHeight());

                    // Convert to PNG bytes
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(thumbnail, "PNG", baos);

                    log.debug("PDF thumbnail rendered, size: {} bytes", baos.size());
                    return baos.toByteArray();
                }
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Converts Office document to PDF using Gotenberg's LibreOffice endpoint.
     * Streams the Resource directly to Gotenberg without loading entire file into memory.
     *
     * @param storagePath Path to the document in storage
     * @param filename    Original filename (needed by Gotenberg for format detection)
     * @return Mono containing the converted PDF bytes
     */
    private Mono<byte[]> convertToPdfWithGotenbergStreaming(String storagePath, String filename) {
        return storageService.loadFile(storagePath)
            .flatMap(resource -> {
                log.debug("Streaming document to Gotenberg for PDF conversion: {}", filename);

                // Create a Resource wrapper that provides the correct filename
                // Gotenberg needs the filename to detect the document type
                Resource namedResource = new NamedInputStreamResource(resource, filename);

                // Build multipart body with the Resource (will be streamed, not loaded into memory)
                MultipartBodyBuilder builder = new MultipartBodyBuilder();
                builder.part("files", namedResource)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);

                // Request only first page for thumbnail
                builder.part("nativePageRanges", "1");

                return getGotenbergClient()
                    .post()
                    .uri("/forms/libreoffice/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .timeout(Duration.ofSeconds(thumbnailProperties.getGotenberg().getTimeoutSeconds()))
                    .retryWhen(Retry.backoff(2, Duration.ofSeconds(2))
                        .maxBackoff(Duration.ofSeconds(10))
                        .doBeforeRetry(signal ->
                            log.warn("Retrying Gotenberg request, attempt {}", signal.totalRetries() + 1)))
                    .doOnSuccess(pdf -> log.debug("Gotenberg conversion complete, PDF size: {} bytes", pdf.length))
                    .doOnError(e -> log.error("Gotenberg conversion failed: {}", e.getMessage()));
            });
    }

    /**
     * Renders the first page of a PDF as a PNG thumbnail using PDFBox.
     * Used for PDF bytes received from Gotenberg conversion.
     */
    private Mono<byte[]> renderPdfThumbnail(byte[] pdfBytes) {
        return Mono.fromCallable(() -> {
            log.debug("Rendering PDF thumbnail with PDFBox ({} bytes)", pdfBytes.length);

            try (PDDocument document = Loader.loadPDF(pdfBytes)) {
                if (document.getNumberOfPages() == 0) {
                    throw new IOException("PDF has no pages");
                }

                PDFRenderer renderer = new PDFRenderer(document);
                // Render at 72 DPI for reasonable quality/speed trade-off
                BufferedImage pageImage = renderer.renderImageWithDPI(0, 72);

                // Resize to thumbnail dimensions while maintaining aspect ratio
                BufferedImage thumbnail = resizeImage(pageImage,
                    thumbnailProperties.getDimensions().getWidth(),
                    thumbnailProperties.getDimensions().getHeight());

                // Convert to PNG bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "PNG", baos);

                log.debug("PDF thumbnail rendered, size: {} bytes", baos.size());
                return baos.toByteArray();
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // ========================================================================
    // Text File Thumbnail Generation
    // ========================================================================

    /**
     * Maximum number of lines to read from a text file for thumbnail generation.
     */
    private static final int MAX_LINES_FOR_THUMBNAIL = 30;

    /**
     * Maximum characters per line to display in thumbnail.
     */
    private static final int MAX_CHARS_PER_LINE = 60;

    /**
     * Renders a text file thumbnail by reading the first N lines and rendering them
     * as an image using Java 2D. This is very efficient as it only reads a small
     * portion of the file and doesn't require any external services.
     */
    private Mono<byte[]> renderTextThumbnailFromStorage(String storagePath) {
        return storageService.loadFile(storagePath)
            .flatMap(resource -> Mono.fromCallable(() -> {
                log.debug("Rendering text thumbnail from storage: {}", storagePath);

                // Read first N lines from the file
                List<String> lines = readFirstLines(resource, MAX_LINES_FOR_THUMBNAIL);

                // Render lines to image
                BufferedImage thumbnail = renderTextToImage(lines,
                    thumbnailProperties.getDimensions().getWidth(),
                    thumbnailProperties.getDimensions().getHeight());

                // Convert to PNG bytes
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(thumbnail, "PNG", baos);

                log.debug("Text thumbnail rendered, size: {} bytes", baos.size());
                return baos.toByteArray();
            }).subscribeOn(Schedulers.boundedElastic()));
    }

    /**
     * Reads the first N lines from a resource. Stops early if the file has fewer lines.
     * This is memory-efficient as it doesn't load the entire file.
     */
    private List<String> readFirstLines(org.springframework.core.io.Resource resource, int maxLines) throws IOException {
        List<String> lines = new ArrayList<>(maxLines);

        try (InputStream is = resource.getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {

            String line;
            while (lines.size() < maxLines && (line = reader.readLine()) != null) {
                // Truncate long lines and replace tabs with spaces
                line = line.replace("\t", "    ");
                if (line.length() > MAX_CHARS_PER_LINE) {
                    line = line.substring(0, MAX_CHARS_PER_LINE - 3) + "...";
                }
                lines.add(line);
            }
        }

        return lines;
    }

    /**
     * Renders text lines to a BufferedImage with a code-editor-like appearance.
     * Uses a monospace font on a light background with subtle styling.
     */
    private BufferedImage renderTextToImage(List<String> lines, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = image.createGraphics();

        // Enable anti-aliasing for smooth text
        g2d.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        // Background - light gray like a code editor
        Color bgColor = new Color(250, 250, 250);
        g2d.setColor(bgColor);
        g2d.fillRect(0, 0, width, height);

        // Draw a subtle border
        g2d.setColor(new Color(220, 220, 220));
        g2d.drawRect(0, 0, width - 1, height - 1);

        // Calculate font size based on thumbnail dimensions
        // Smaller thumbnails need smaller fonts
        int fontSize = Math.max(8, Math.min(11, height / 20));
        Font monoFont = new Font(Font.MONOSPACED, Font.PLAIN, fontSize);
        g2d.setFont(monoFont);

        FontMetrics fm = g2d.getFontMetrics();
        int lineHeight = fm.getHeight();
        int padding = 6;
        int y = padding + fm.getAscent();

        // Text color - dark gray
        Color textColor = new Color(60, 60, 60);
        g2d.setColor(textColor);

        // Draw each line
        for (String line : lines) {
            if (y + lineHeight > height - padding) {
                // No more space, stop drawing
                break;
            }
            g2d.drawString(line, padding, y);
            y += lineHeight;
        }

        // If there are more lines than we can display, show an indicator
        if (!lines.isEmpty() && y + lineHeight <= height - padding) {
            // Check if we might have more content (we read MAX_LINES)
            if (lines.size() >= MAX_LINES_FOR_THUMBNAIL) {
                g2d.setColor(new Color(150, 150, 150));
                g2d.drawString("...", padding, y);
            }
        }

        g2d.dispose();
        return image;
    }

    /**
     * Resizes an image to fit within the specified dimensions while maintaining aspect ratio.
     */
    private BufferedImage resizeImage(BufferedImage original, int targetWidth, int targetHeight) {
        int originalWidth = original.getWidth();
        int originalHeight = original.getHeight();

        // Calculate scaling to fit within target dimensions
        double scaleX = (double) targetWidth / originalWidth;
        double scaleY = (double) targetHeight / originalHeight;
        double scale = Math.min(scaleX, scaleY);

        int newWidth = (int) (originalWidth * scale);
        int newHeight = (int) (originalHeight * scale);

        // Create thumbnail with white background
        BufferedImage thumbnail = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2d = thumbnail.createGraphics();

        // White background
        g2d.setColor(Color.WHITE);
        g2d.fillRect(0, 0, targetWidth, targetHeight);

        // High quality rendering
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // Center the image
        int x = (targetWidth - newWidth) / 2;
        int y = (targetHeight - newHeight) / 2;

        g2d.drawImage(original, x, y, newWidth, newHeight, null);
        g2d.dispose();

        return thumbnail;
    }

    /**
     * Builds the ImgProxy request path.
     * Format: /rs:fit:{width}:{height}/plain/{source_url}@{extension}
     */
    private Mono<String> buildImgProxyPath(Document document) {
        return Mono.fromCallable(() -> {
            String sourceUrl = buildDocumentSourceUrl(document.getId());
            String contentType = document.getContentType();

            // Build processing options
            StringBuilder path = new StringBuilder();

            // Resize option
            path.append("/rs:fit:")
                .append(thumbnailProperties.getDimensions().getWidth())
                .append(":")
                .append(thumbnailProperties.getDimensions().getHeight());

            // Plain URL mode with extension hint
            path.append("/plain/").append(sourceUrl);

            // Add extension hint for type detection
            String extension = getExtensionFromContentType(contentType);
            if (extension != null) {
                path.append("@").append(extension);
            }

            return path.toString();
        });
    }

    /**
     * Gets file extension from content type for ImgProxy type detection.
     */
    private String getExtensionFromContentType(String contentType) {
        if (contentType == null) return null;
        String ct = contentType.toLowerCase();
        if (ct.contains("jpeg") || ct.contains("jpg")) return "jpg";
        if (ct.contains("png")) return "png";
        if (ct.contains("gif")) return "gif";
        if (ct.contains("webp")) return "webp";
        if (ct.contains("bmp")) return "bmp";
        if (ct.contains("tiff")) return "tiff";
        return null;
    }

    /**
     * Builds the source URL that ImgProxy will use to fetch the document.
     * This URL points to our /api/v1/thumbnails/source/{id} endpoint.
     */
    private String buildDocumentSourceUrl(UUID documentId) {
        return UriComponentsBuilder.fromUriString(commonProperties.getApiInternalBaseUrl())
            .path(API_PREFIX + ENDPOINT_THUMBNAILS + "/source/")
            .path(documentId.toString())
            .build()
            .toUriString();
    }

    /**
     * Fetches thumbnail from ImgProxy.
     */
    private Mono<byte[]> fetchThumbnailFromImgProxy(String path) {
        log.debug("Fetching thumbnail from ImgProxy: {}", path);
        return getImgProxyClient()
            .get()
            .uri(path)
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(30))
            .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .doBeforeRetry(signal ->
                    log.warn("Retrying ImgProxy request, attempt {}", signal.totalRetries() + 1)));
    }

    @Override
    public Mono<byte[]> getThumbnail(UUID documentId) {
        return thumbnailStorage.loadThumbnail(documentId);
    }

    @Override
    public Mono<Void> deleteThumbnail(UUID documentId) {
        return thumbnailStorage.deleteThumbnail(documentId);
    }

    @Override
    public Mono<Void> copyThumbnail(UUID sourceDocumentId, UUID targetDocumentId) {
        return thumbnailStorage.copyThumbnail(sourceDocumentId, targetDocumentId);
    }

    @Override
    public boolean isSupported(String contentType) {
        return thumbnailProperties.isContentTypeSupported(contentType);
    }

    @Override
    public Mono<Boolean> thumbnailExists(UUID documentId) {
        return thumbnailStorage.thumbnailExists(documentId);
    }

    /**
     * A Resource wrapper that delegates to another Resource but provides a custom filename.
     * This is needed for Gotenberg to correctly detect the document type from the filename.
     */
    private static class NamedInputStreamResource implements Resource {
        private final Resource delegate;
        private final String filename;

        NamedInputStreamResource(Resource delegate, String filename) {
            this.delegate = delegate;
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            return delegate.getInputStream();
        }

        @Override
        public boolean exists() {
            return delegate.exists();
        }

        @Override
        public java.net.URL getURL() throws IOException {
            return delegate.getURL();
        }

        @Override
        public java.net.URI getURI() throws IOException {
            return delegate.getURI();
        }

        @Override
        public java.io.File getFile() throws IOException {
            return delegate.getFile();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public long lastModified() throws IOException {
            return delegate.lastModified();
        }

        @Override
        public Resource createRelative(String relativePath) throws IOException {
            return delegate.createRelative(relativePath);
        }

        @Override
        public String getDescription() {
            return "Named resource [" + filename + "] wrapping " + delegate.getDescription();
        }
    }
}
