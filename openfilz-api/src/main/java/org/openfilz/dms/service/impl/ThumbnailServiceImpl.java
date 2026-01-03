package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.openfilz.dms.config.CommonProperties;
import org.openfilz.dms.config.ThumbnailProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.StorageService;
import org.openfilz.dms.service.ThumbnailService;
import org.openfilz.dms.service.ThumbnailStorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * Implementation of ThumbnailService that uses:
 * - ImgProxy for image thumbnails (JPEG, PNG, GIF, WebP, etc.)
 * - Gotenberg + PDFBox for PDF and Office document thumbnails
 * <p>
 * Flow for images:
 * 1. Build ImgProxy URL with source document URL
 * 2. ImgProxy fetches document from /api/v1/thumbnails/source/{id}
 * 3. ImgProxy generates thumbnail and returns it
 * 4. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for PDFs:
 * 1. Load PDF from storage
 * 2. Use PDFBox to render first page as image
 * 3. Resize to thumbnail dimensions
 * 4. Store thumbnail via ThumbnailStorageService
 * <p>
 * Flow for Office documents:
 * 1. Load document from storage
 * 2. Send to Gotenberg LibreOffice to convert to PDF
 * 3. Use PDFBox to render first page as image
 * 4. Resize to thumbnail dimensions
 * 5. Store thumbnail via ThumbnailStorageService
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
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(50 * 1024 * 1024)) // 50MB
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
        } else if (thumbnailProperties.shouldUseGotenberg(document.getContentType())) {
            // PDFs and Office documents: use Gotenberg + PDFBox
            log.debug("Using Gotenberg/PDFBox for document: {} (type: {})", document.getId(), document.getContentType());
            thumbnailBytes = generateThumbnailWithGotenberg(document);
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
     * Generates thumbnail for PDF or Office documents.
     * - PDFs: Direct rendering with PDFBox
     * - Office docs: Convert to PDF with Gotenberg, then render with PDFBox
     */
    private Mono<byte[]> generateThumbnailWithGotenberg(Document document) {
        String contentType = document.getContentType().toLowerCase();

        if (contentType.contains("pdf")) {
            // PDF: Load and render directly with PDFBox
            return loadDocumentBytes(document.getStoragePath())
                .flatMap(this::renderPdfThumbnail);
        } else {
            // Office document: Convert to PDF with Gotenberg, then render with PDFBox
            return loadDocumentBytes(document.getStoragePath())
                .flatMap(docBytes -> convertToPdfWithGotenberg(docBytes, document.getName()))
                .flatMap(this::renderPdfThumbnail);
        }
    }

    /**
     * Loads document bytes from storage.
     */
    private Mono<byte[]> loadDocumentBytes(String storagePath) {
        return storageService.loadFile(storagePath)
            .flatMap(resource -> {
                if (resource instanceof org.springframework.core.io.buffer.DataBufferFactory) {
                    // Handle reactive resources
                    return Mono.error(new UnsupportedOperationException("Reactive resource not directly supported"));
                }
                // For standard resources, read content
                return Mono.fromCallable(() -> {
                    try (var inputStream = resource.getInputStream()) {
                        return inputStream.readAllBytes();
                    }
                }).subscribeOn(Schedulers.boundedElastic());
            });
    }

    /**
     * Converts Office document to PDF using Gotenberg's LibreOffice endpoint.
     */
    private Mono<byte[]> convertToPdfWithGotenberg(byte[] docBytes, String filename) {
        log.debug("Converting document to PDF with Gotenberg: {} ({} bytes)", filename, docBytes.length);

        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("files", new org.springframework.core.io.ByteArrayResource(docBytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        }).contentType(MediaType.APPLICATION_OCTET_STREAM);

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
    }

    /**
     * Renders the first page of a PDF as a PNG thumbnail using PDFBox.
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
}
