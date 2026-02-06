package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.dto.request.TusFinalizeRequest;
import org.openfilz.dms.dto.response.TusUploadInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.TusUploadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * REST Controller for TUS (resumable upload) protocol endpoints.
 *
 * TUS is an open protocol for resumable file uploads that enables reliable
 * uploads of large files by splitting them into chunks. This is particularly
 * useful for bypassing Cloudflare's 100MB upload limit.
 *
 * Protocol reference: https://tus.io/protocols/resumable-upload.html
 *
 * This implementation is fully reactive and works with WebFlux/Netty.
 *
 * Endpoints:
 * - OPTIONS /api/v1/tus - TUS capability discovery
 * - POST /api/v1/tus - Create new upload (returns Location header with upload URL)
 * - HEAD /api/v1/tus/{uploadId} - Get upload progress/offset
 * - PATCH /api/v1/tus/{uploadId} - Upload chunk data
 * - DELETE /api/v1/tus/{uploadId} - Cancel upload
 * - POST /api/v1/tus/{uploadId}/finalize - Complete upload and create Document
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_TUS)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
@ConditionalOnProperty(name = "openfilz.tus.enabled", havingValue = "true", matchIfMissing = true)
public class TusController {

    private static final String TUS_VERSION = "1.0.0";
    private static final String TUS_EXTENSION = "creation,termination";

    private final TusUploadService tusUploadService;
    private final TusProperties tusProperties;

    /**
     * TUS capability discovery endpoint.
     * Returns TUS protocol headers indicating supported features.
     */
    @RequestMapping(method = RequestMethod.OPTIONS)
    @Operation(summary = "TUS capability discovery",
            description = "Returns TUS protocol headers indicating supported extensions and version.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "TUS capabilities returned in headers",
                    headers = {
                            @Header(name = "Tus-Resumable", description = "TUS protocol version", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Tus-Version", description = "Supported TUS versions", schema = @Schema(type = "string", example = "1.0.0")),
                            @Header(name = "Tus-Extension", description = "Supported TUS extensions", schema = @Schema(type = "string", example = "creation,termination")),
                            @Header(name = "Tus-Max-Size", description = "Maximum upload size in bytes", schema = @Schema(type = "integer"))
                    })
    })
    public Mono<ResponseEntity<Void>> options() {
        return Mono.just(ResponseEntity.noContent()
                .header("Tus-Resumable", TUS_VERSION)
                .header("Tus-Version", TUS_VERSION)
                .header("Tus-Extension", TUS_EXTENSION)
                .header("Tus-Max-Size", String.valueOf(tusProperties.getMaxUploadSize()))
                .build());
    }

    /**
     * Create a new TUS upload.
     * The client must specify Upload-Length header with the total file size.
     * Upload-Metadata header should contain: filename, parentFolderId (optional), allowDuplicateFileNames (optional)
     */
    @PostMapping
    @Operation(summary = "Create new TUS upload",
            description = "Creates a new TUS upload session. Client must provide Upload-Length header. " +
                    "Upload-Metadata header should contain base64-encoded values for: filename (required), " +
                    "parentFolderId (optional), allowDuplicateFileNames (optional, default false).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Upload created",
                    headers = {
                            @Header(name = "Location", description = "URL to upload chunks to", schema = @Schema(type = "string")),
                            @Header(name = "Tus-Resumable", description = "TUS protocol version", schema = @Schema(type = "string"))
                    }),
            @ApiResponse(responseCode = "413", description = "Upload size exceeds maximum allowed or file quota exceeded"),
            @ApiResponse(responseCode = "507", description = "User storage quota exceeded"),
            @ApiResponse(responseCode = "404", description = "Parent folder not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate filename in target folder"),
            @ApiResponse(responseCode = "400", description = "Missing required headers or filename")
    })
    public Mono<ResponseEntity<Void>> createUpload(
            @RequestHeader("Upload-Length") Long uploadLength,
            @RequestHeader(value = "Upload-Metadata", required = false) String metadata,
            ServerHttpRequest request) {

        log.debug("TUS POST - Creating new upload with length: {}", uploadLength);

        // Validate upload length against TUS max size
        if (uploadLength > tusProperties.getMaxUploadSize()) {
            return Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .header("Tus-Resumable", TUS_VERSION)
                    .build());
        }

        // Extract metadata values
        TusMetadataValues metadataValues = parseMetadataHeader(metadata);

        // Filename is required for validation
        if (metadataValues.filename == null || metadataValues.filename.isBlank()) {
            log.warn("TUS upload creation rejected: filename is required in Upload-Metadata header");
            return Mono.just(ResponseEntity.badRequest()
                    .header("Tus-Resumable", TUS_VERSION)
                    .build());
        }

        String baseUrl = getBaseUrl(request);

        // Validate all preconditions before creating the upload
        return tusUploadService.validateUploadCreation(
                        uploadLength,
                        metadataValues.filename,
                        metadataValues.parentFolderId,
                        metadataValues.allowDuplicateFileNames)
                .then(tusUploadService.createUpload(uploadLength, metadata))
                .map(uploadId -> {
                    String location = baseUrl + "/" + uploadId;
                    log.info("TUS upload created: {} -> {}", uploadId, location);
                    return ResponseEntity.created(URI.create(location))
                            .header("Tus-Resumable", TUS_VERSION)
                            .header("Upload-Offset", "0")
                            .<Void>build();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to create upload: {}", e.getMessage());
                    String errorType = e.getClass().getSimpleName();
                    return switch (errorType) {
                        case "FileSizeExceededException" -> Mono.just(ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                        case "UserQuotaExceededException" -> Mono.just(ResponseEntity.status(HttpStatus.INSUFFICIENT_STORAGE)
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                        case "DocumentNotFoundException" -> Mono.just(ResponseEntity.notFound()
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                        case "DuplicateNameException" -> Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                        default -> Mono.error(e);
                    };
                });
    }

    /**
     * Parsed values from TUS Upload-Metadata header.
     */
    private record TusMetadataValues(
            String filename,
            UUID parentFolderId,
            Boolean allowDuplicateFileNames
    ) {}

    /**
     * Parse TUS Upload-Metadata header.
     * Format: key1 base64value1,key2 base64value2,...
     * Expected keys: filename, parentFolderId, allowDuplicateFileNames
     */
    private TusMetadataValues parseMetadataHeader(String metadata) {
        String filename = null;
        UUID parentFolderId = null;
        Boolean allowDuplicateFileNames = false;

        if (metadata == null || metadata.isBlank()) {
            return new TusMetadataValues(null, null, false);
        }

        try {
            String[] pairs = metadata.split(",");
            for (String pair : pairs) {
                String[] parts = pair.trim().split(" ", 2);
                if (parts.length < 1) continue;

                String key = parts[0].trim().toLowerCase();
                String value = parts.length == 2
                        ? new String(java.util.Base64.getDecoder().decode(parts[1].trim()))
                        : "";

                switch (key) {
                    case "filename" -> filename = value;
                    case "parentfolderid" -> {
                        if (!value.isBlank()) {
                            try {
                                parentFolderId = UUID.fromString(value);
                            } catch (IllegalArgumentException e) {
                                log.debug("Invalid parentFolderId in TUS metadata: {}", value);
                            }
                        }
                    }
                    case "allowduplicatefilenames" -> allowDuplicateFileNames = "true".equalsIgnoreCase(value);
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("Could not parse TUS metadata: {}", e.getMessage());
        }

        return new TusMetadataValues(filename, parentFolderId, allowDuplicateFileNames);
    }

    /**
     * Get upload progress/offset.
     * Returns the current offset (number of bytes received).
     */
    @RequestMapping(value = "/{uploadId}", method = RequestMethod.HEAD)
    @Operation(summary = "Get upload progress",
            description = "Returns the current upload offset (bytes received).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload info returned",
                    headers = {
                            @Header(name = "Upload-Offset", description = "Current offset in bytes", schema = @Schema(type = "integer")),
                            @Header(name = "Upload-Length", description = "Total file size in bytes", schema = @Schema(type = "integer")),
                            @Header(name = "Tus-Resumable", description = "TUS protocol version", schema = @Schema(type = "string"))
                    }),
            @ApiResponse(responseCode = "404", description = "Upload not found")
    })
    public Mono<ResponseEntity<Void>> getUploadOffset(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId) {

        log.debug("TUS HEAD - Getting offset for upload: {}", uploadId);

        return Mono.zip(
                tusUploadService.getUploadOffset(uploadId),
                tusUploadService.getUploadLength(uploadId)
        ).map(tuple -> ResponseEntity.ok()
                .header("Tus-Resumable", TUS_VERSION)
                .header("Upload-Offset", tuple.getT1().toString())
                .header("Upload-Length", tuple.getT2().toString())
                .header("Cache-Control", "no-store")
                .<Void>build()
        ).onErrorResume(e -> {
            log.debug("Upload not found: {}", uploadId);
            return Mono.just(ResponseEntity.notFound()
                    .header("Tus-Resumable", TUS_VERSION)
                    .build());
        });
    }

    /**
     * Upload chunk data.
     * Client must send Upload-Offset header matching current server offset.
     */
    @PatchMapping(value = "/{uploadId}", consumes = "application/offset+octet-stream")
    @Operation(summary = "Upload chunk",
            description = "Uploads a chunk of data. Client must send Upload-Offset header matching current offset.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Chunk received successfully",
                    headers = {
                            @Header(name = "Upload-Offset", description = "New offset after this chunk", schema = @Schema(type = "integer")),
                            @Header(name = "Tus-Resumable", description = "TUS protocol version", schema = @Schema(type = "string"))
                    }),
            @ApiResponse(responseCode = "409", description = "Offset mismatch - resume from HEAD request"),
            @ApiResponse(responseCode = "404", description = "Upload not found")
    })
    public Mono<ResponseEntity<Void>> uploadChunk(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId,
            @RequestHeader("Upload-Offset") Long offset,
            @RequestHeader(value = "Content-Length", required = false) Long contentLength,
            @RequestBody Flux<DataBuffer> body) {

        log.debug("TUS PATCH - Uploading chunk for: {} at offset: {}", uploadId, offset);

        return tusUploadService.uploadChunk(uploadId, offset, body)
                .map(newOffset -> ResponseEntity.noContent()
                        .header("Tus-Resumable", TUS_VERSION)
                        .header("Upload-Offset", newOffset.toString())
                        .<Void>build())
                .onErrorResume(e -> {
                    log.warn("Error uploading chunk for {}: {}", uploadId, e.getMessage());
                    if (e.getMessage() != null && e.getMessage().contains("Offset mismatch")) {
                        return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT)
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                    }
                    if (e.getMessage() != null && e.getMessage().contains("not found")) {
                        return Mono.just(ResponseEntity.notFound()
                                .header("Tus-Resumable", TUS_VERSION)
                                .build());
                    }
                    return Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                            .header("Tus-Resumable", TUS_VERSION)
                            .build());
                });
    }

    /**
     * Cancel and delete an upload.
     */
    @DeleteMapping("/{uploadId}")
    @Operation(summary = "Cancel upload",
            description = "Cancels an in-progress upload and cleans up temporary data.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "204", description = "Upload cancelled successfully"),
            @ApiResponse(responseCode = "404", description = "Upload not found")
    })
    public Mono<ResponseEntity<Void>> cancelUpload(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId) {

        log.debug("TUS DELETE - Cancelling upload: {}", uploadId);

        return tusUploadService.cancelUpload(uploadId)
                .then(Mono.just(ResponseEntity.noContent()
                        .header("Tus-Resumable", TUS_VERSION)
                        .<Void>build()));
    }

    /**
     * Get upload info (reactive endpoint for frontend integration).
     */
    @GetMapping("/{uploadId}/info")
    @Operation(summary = "Get upload information",
            description = "Returns detailed information about an upload including offset, length, and expiration.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Upload information returned"),
            @ApiResponse(responseCode = "404", description = "Upload not found")
    })
    public Mono<ResponseEntity<TusUploadInfo>> getUploadInfo(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId,
            ServerHttpRequest request) {

        String baseUrl = getBaseUrl(request);
        return tusUploadService.getUploadInfo(uploadId, baseUrl)
                .map(ResponseEntity::ok);
    }

    /**
     * Finalize a completed upload by creating the Document entity.
     * This endpoint should be called after all chunks have been uploaded.
     */
    @PostMapping("/{uploadId}/finalize")
    @Operation(summary = "Finalize upload",
            description = "Completes the upload by moving the file to permanent storage and creating the Document record. " +
                    "Call this after all chunks have been successfully uploaded.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Document created successfully"),
            @ApiResponse(responseCode = "400", description = "Upload is not complete"),
            @ApiResponse(responseCode = "404", description = "Upload not found"),
            @ApiResponse(responseCode = "409", description = "Duplicate filename in target folder"),
            @ApiResponse(responseCode = "413", description = "File size exceeds quota"),
            @ApiResponse(responseCode = "507", description = "User storage quota exceeded")
    })
    public Mono<ResponseEntity<UploadResponse>> finalizeUpload(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId,
            @Valid @RequestBody TusFinalizeRequest request) {

        log.info("TUS FINALIZE - Finalizing upload: {} with filename: {}", uploadId, request.filename());

        return tusUploadService.finalizeUpload(uploadId, request)
                .map(response -> ResponseEntity.status(HttpStatus.CREATED).body(response));
    }

    /**
     * Check if an upload is complete.
     */
    @GetMapping("/{uploadId}/complete")
    @Operation(summary = "Check upload completion",
            description = "Returns whether the upload has received all bytes.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Completion status returned"),
            @ApiResponse(responseCode = "404", description = "Upload not found")
    })
    public Mono<ResponseEntity<Boolean>> isUploadComplete(
            @Parameter(description = "Upload identifier") @PathVariable String uploadId) {

        return tusUploadService.isUploadComplete(uploadId)
                .map(ResponseEntity::ok);
    }

    /**
     * Get TUS configuration for client-side setup.
     */
    @GetMapping("/config")
    @Operation(summary = "Get TUS configuration",
            description = "Returns configuration values for client-side TUS setup.")
    public Mono<ResponseEntity<TusConfigResponse>> getConfig(ServerHttpRequest request) {
        String baseUrl = getBaseUrl(request);
        return Mono.just(ResponseEntity.ok(new TusConfigResponse(
                tusProperties.isEnabled(),
                baseUrl,
                tusProperties.getMaxUploadSize(),
                tusProperties.getChunkSize(),
                tusProperties.getUploadExpirationPeriod()
        )));
    }

    private String getBaseUrl(ServerHttpRequest request) {
        URI uri = request.getURI();
        String scheme = uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort();

        StringBuilder baseUrl = new StringBuilder();
        baseUrl.append(scheme).append("://").append(host);

        if (port > 0 && !((scheme.equals("http") && port == 80) || (scheme.equals("https") && port == 443))) {
            baseUrl.append(":").append(port);
        }

        baseUrl.append(RestApiVersion.API_PREFIX)
                .append(RestApiVersion.ENDPOINT_TUS);

        return baseUrl.toString();
    }

    /**
     * Response DTO for TUS configuration.
     */
    public record TusConfigResponse(
            boolean enabled,
            String endpoint,
            long maxUploadSize,
            long chunkSize,
            long uploadExpirationPeriod
    ) {}
}
