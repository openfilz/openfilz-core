package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.ThumbnailService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * REST controller for thumbnail operations.
 * <p>
 * Provides two endpoints:
 * - GET /api/v1/thumbnails/{documentId} - Serves thumbnail to frontend (OAuth2 protected)
 * - GET /api/v1/thumbnails/source/{documentId} - Serves original document to ImgProxy (mTLS protected)
 */
@Slf4j
@RestController
@RequestMapping(API_PREFIX + ENDPOINT_THUMBNAILS)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "true")
public class ThumbnailController {

    private final ThumbnailService thumbnailService;
    private final DocumentService documentService;

    /**
     * Serves the thumbnail for a document.
     * Protected by standard OAuth2 authentication.
     *
     * @param documentId the document ID
     * @return the thumbnail bytes as PNG image
     */
    @GetMapping("/img/{documentId}")
    @Operation(summary = "Get document thumbnail",
            description = "Returns the thumbnail image for a document. Returns 404 if thumbnail doesn't exist.")
    public Mono<ResponseEntity<Resource>> getThumbnail(@PathVariable UUID documentId) {
        return thumbnailService.getThumbnail(documentId)
                .map(bytes -> {
                    ByteArrayResource resource = new ByteArrayResource(bytes);
                    return ResponseEntity.ok()
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.IMAGE_PNG_VALUE)
                            .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                            .contentLength(bytes.length)
                            .body((Resource) resource);
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /**
     * Serves the original document to ImgProxy for thumbnail generation.
     * This endpoint is protected by mTLS - only ImgProxy with valid client certificate can access it.
     *
     * @param documentId the document ID
     * @return the document content
     */
    @GetMapping("/source/{documentId}")
    @Operation(summary = "Get document source for thumbnail generation",
            description = "Internal endpoint for ImgProxy to fetch document content. Protected by mTLS.")
    public Mono<ResponseEntity<Resource>> getDocumentSource(@PathVariable UUID documentId) {
        return documentService.findDocumentToDownloadById(documentId)
                .flatMap(document -> documentService.downloadDocument(document)
                        .map(resource -> ResponseEntity.ok()
                                .header(HttpHeaders.CONTENT_TYPE,
                                        document.getContentType() != null
                                                ? document.getContentType()
                                                : MediaType.APPLICATION_OCTET_STREAM_VALUE)
                                .header(HttpHeaders.CONTENT_DISPOSITION,
                                        "inline; filename=\"" + document.getName() + "\"")
                                .body(resource)));
    }
}
