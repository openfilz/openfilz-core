package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ThumbnailService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.openfilz.dms.utils.ImageUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Implementation of ThumbnailService that uses:
 * - Custom WebP conversion for image thumbnails (JPEG, PNG, GIF, WebP, etc.)
 * - PDFBox for PDF thumbnails
 * - Gotenberg + PDFBox for Office document thumbnails
 * - Java 2D for text file thumbnails (markdown, code, config files, etc.)
 * <p>
 * Flow for images:
 * 1. Resize image to 100x100 max
 * 2. Convert & compress to WebP
 * 3. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for PDFs:
 * 1. Stream PDF from storage directly to PDFBox
 * 2. Use PDFBox to render first page as image
 * 3. Resize to thumbnail dimensions
 * 4. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for Office documents:
 * 1. Stream document from storage directly to Gotenberg
 * 2. Gotenberg converts to PDF using LibreOffice
 * 3. Send PDF to Gotenberg split endpoint to extract only first page
 * 4. Use PDFBox to render first page as image (small single-page PDF)
 * 5. Resize to thumbnail dimensions
 * 6. Store thumbnail via ThumbnailStorageService
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

    private static final String WEBP_FORMAT = "webp";
    private final ThumbnailStorageService thumbnailStorage;
    private final ThumbnailProperties thumbnailProperties;
    private final WebClient.Builder webClientBuilder;
    private final StorageService storageService;

    private WebClient gotenbergClient;

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
        final String extension;
        if (thumbnailProperties.shouldUseWebpConversion(document.getContentType())) {
            // Images: use Custom WebP conversion
            extension = WEBP_FORMAT;
            log.debug("Using custom WebP generation for images");
            thumbnailBytes = storageService.loadFile(document.getStoragePath())
                    .publishOn(Schedulers.boundedElastic())
                    .flatMap(ImageUtils::resourceToWebpThumbnail);
        } else {
            extension = null;
            if (thumbnailProperties.shouldUsePdfBox(document.getContentType())) {
                // PDFs: use PDFBox directly
                log.debug("Using PDFBox for document: {} (type: {})", document.getId(), document.getContentType());
                thumbnailBytes = renderPdfThumbnailFromStorage(document.getStoragePath());
            } else if (thumbnailProperties.shouldUseGotenberg(document.getContentType())) {
                // Office documents: use Gotenberg (convert + split) + PDFBox
                // 1. Convert Office doc to PDF via LibreOffice (stream to temp file)
                // 2. Split PDF to extract only first page (stream from temp file)
                // 3. Render first page to thumbnail with PDFBox
                // Note: Full PDF is never loaded into memory - uses temp file as intermediate storage
                log.debug("Using Gotenberg for document: {} (type: {})", document.getId(), document.getContentType());
                thumbnailBytes = convertAndExtractFirstPageWithGotenberg(document.getStoragePath(), document.getName())
                        .flatMap(this::renderPdfThumbnail);
            } else if (thumbnailProperties.shouldUseTextRenderer(document.getContentType())) {
                // Text files: use Java 2D text rendering
                log.debug("Using text renderer for document: {} (type: {})", document.getId(), document.getContentType());
                thumbnailBytes = renderTextThumbnailFromStorage(document.getStoragePath());
            } else {
                log.debug("No thumbnail handler for content type: {}", document.getContentType());
                return Mono.empty();
            }
        }

        return thumbnailBytes
            .flatMap(bytes -> thumbnailStorage.saveThumbnail(document.getId(), bytes, extension))
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
     * Converts Office document to PDF and extracts only the first page.
     * Uses temp file to avoid loading full PDF into memory:
     * 1. Stream document to Gotenberg for LibreOffice conversion
     * 2. Stream PDF response directly to temp file (not into byte[])
     * 3. Stream temp file to Gotenberg split endpoint to extract page 1
     * 4. Return only the single-page PDF (small, safe to hold in memory)
     * 5. Clean up temp file
     *
     * @param storagePath Path to the document in storage
     * @param filename    Original filename (needed by Gotenberg for format detection)
     * @return Mono containing the first page as PDF bytes
     */
    private Mono<byte[]> convertAndExtractFirstPageWithGotenberg(String storagePath, String filename) {
        return storageService.loadFile(storagePath)
            .flatMap(resource -> {
                log.debug("Converting document to PDF with Gotenberg: {}", filename);

                // Buffer the source document to support retries (source doc is usually small compared to PDF)
                return DataBufferUtils.join(DataBufferUtils.read(resource, new DefaultDataBufferFactory(), 8192))
                    .map(dataBuffer -> {
                        byte[] bytes = new byte[dataBuffer.readableByteCount()];
                        dataBuffer.read(bytes);
                        DataBufferUtils.release(dataBuffer);
                        return bytes;
                    });
            })
            .flatMap(documentBytes -> {
                // Create temp file for PDF output
                final Path tempPdfPath;
                try {
                    tempPdfPath = Files.createTempFile("gotenberg-convert-", ".pdf");
                    log.debug("Created temp file for PDF: {}", tempPdfPath);
                } catch (IOException e) {
                    return Mono.error(new RuntimeException("Failed to create temp file for PDF conversion", e));
                }

                // Build multipart request for LibreOffice conversion
                Resource namedResource = new NamedByteArrayResource(documentBytes, filename);
                MultipartBodyBuilder builder = new MultipartBodyBuilder();
                builder.part("files", namedResource)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);

                // Step 1: Convert to PDF and stream response to temp file
                return getGotenbergClient()
                    .post()
                    .uri("/forms/libreoffice/convert")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(builder.build()))
                    .retrieve()
                    .bodyToFlux(DataBuffer.class)
                    .flatMap(dataBuffer -> writeDataBufferToFile(dataBuffer, tempPdfPath))
                    .then(Mono.defer(() -> {
                        // Log the size of the converted PDF
                        try {
                            long pdfSize = Files.size(tempPdfPath);
                            log.debug("Gotenberg conversion complete, PDF written to temp file: {} bytes", pdfSize);
                        } catch (IOException ignored) {}

                        // Step 2: Stream temp file to split endpoint to extract first page
                        return extractFirstPageFromTempFile(tempPdfPath);
                    }))
                    .timeout(Duration.ofSeconds(thumbnailProperties.getGotenberg().getTimeoutSeconds() * 2)) // Allow time for both operations
                    .doFinally(signal -> {
                        // Clean up temp file
                        try {
                            Files.deleteIfExists(tempPdfPath);
                            log.debug("Deleted temp file: {}", tempPdfPath);
                        } catch (IOException e) {
                            log.warn("Failed to delete temp file: {}", tempPdfPath, e);
                        }
                    })
                    .doOnError(e -> log.error("Gotenberg convert+split failed: {}", e.getMessage()));
            });
    }

    /**
     * Writes a DataBuffer to a file path, then releases the buffer.
     */
    private Mono<Void> writeDataBufferToFile(DataBuffer dataBuffer, Path filePath) {
        return Mono.fromCallable(() -> {
            try (var channel = Files.newOutputStream(filePath, StandardOpenOption.APPEND)) {
                byte[] bytes = new byte[dataBuffer.readableByteCount()];
                dataBuffer.read(bytes);
                channel.write(bytes);
                return null;
            } finally {
                DataBufferUtils.release(dataBuffer);
            }
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Extracts only the first page from a PDF file using Gotenberg's split endpoint.
     * Streams directly from file without loading entire PDF into memory.
     *
     * @param pdfPath Path to the PDF temp file
     * @return Mono containing only the first page as PDF bytes
     */
    private Mono<byte[]> extractFirstPageFromTempFile(Path pdfPath) {
        log.debug("Extracting first page from temp PDF file: {}", pdfPath);

        // Use FileSystemResource to stream from file
        FileSystemResource pdfResource = new FileSystemResource(pdfPath);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", pdfResource)
            .filename("document.pdf")
            .contentType(MediaType.APPLICATION_PDF);

        // Use "pages" mode to extract specific pages
        builder.part("splitMode", "pages");
        // Extract only page 1
        builder.part("splitSpan", "1");
        // Unify into a single PDF (not a ZIP)
        builder.part("splitUnify", "true");

        return getGotenbergClient()
            .post()
            .uri("/forms/pdfengines/split")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .retrieve()
            .bodyToMono(byte[].class)
            .timeout(Duration.ofSeconds(thumbnailProperties.getGotenberg().getTimeoutSeconds()))
            .doOnSuccess(pdf -> {
                try {
                    long originalSize = Files.size(pdfPath);
                    log.debug("First page extracted: {} bytes (was {} bytes)", pdf.length, originalSize);
                } catch (IOException ignored) {}
            })
            .doOnError(e -> log.error("Gotenberg split failed: {}", e.getMessage()));
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
                renderer.setSubsamplingAllowed(true);
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


    /**
     * A ByteArrayResource that provides a custom filename.
     * This is needed for Gotenberg to correctly detect the document type from the filename.
     * Unlike InputStreamResource, this can be read multiple times (for retries).
     */
    private static class NamedByteArrayResource extends ByteArrayResource {
        private final String filename;

        NamedByteArrayResource(byte[] byteArray, String filename) {
            super(byteArray);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }

        @Override
        public String getDescription() {
            return "Named byte array resource [" + filename + "]";
        }
    }
}
