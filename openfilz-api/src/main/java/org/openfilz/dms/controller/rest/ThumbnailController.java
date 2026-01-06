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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.config.RestApiVersion.API_PREFIX;
import static org.openfilz.dms.config.RestApiVersion.ENDPOINT_THUMBNAILS;

/**
 * REST controller for thumbnail operations.
 * <p>
 * Provides an endpoint:
 * - GET /api/v1/thumbnails/{documentId} - Serves thumbnail to frontend (OAuth2 protected)
 */
@Slf4j
@RestController
@RequestMapping(API_PREFIX + ENDPOINT_THUMBNAILS)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.thumbnail.active", havingValue = "true")
public class ThumbnailController {

    private final ThumbnailService thumbnailService;

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
                            .header(HttpHeaders.CONTENT_TYPE, "image/*")
                            .header(HttpHeaders.CACHE_CONTROL, "max-age=3600")
                            .contentLength(bytes.length)
                            .body((Resource) resource);
                })
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

}
