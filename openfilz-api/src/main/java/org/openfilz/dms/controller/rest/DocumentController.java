package org.openfilz.dms.controller.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.converter.CustomJsonPart;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.ElementInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.service.DocumentService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.openfilz.dms.controller.rest.ApiDescription.ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION;
import static org.openfilz.dms.enums.DocumentType.FILE;

@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class DocumentController {

    public static final String ATTACHMENT_ZIP = "attachment; filename=\"documents.zip\"";
    public static final String ZIP = ".zip";

    private final DocumentService documentService;

    private final ObjectMapper objectMapper; // For parsing metadata string

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a single document",
            description = "Uploads a single file, optionally with metadata and a parent folder ID.")
    public Mono<ResponseEntity<UploadResponse>> uploadDocument(
            @RequestPart("file") FilePart filePart,
            @Parameter(description = "Target parent folder ID to store the file; if not sent or null, the file is stored at the root level") @RequestPart(name = "parentFolderId", required = false) String parentFolderId,
            @RequestPart(name = "metadata", required = false) String metadataJson,
            @Parameter(hidden = true) @RequestHeader(name = "Content-Length", required = false) Long contentLength,
            @Parameter(description = ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION) @RequestParam(required = false, defaultValue = "false") Boolean allowDuplicateFileNames
            ) {

        return documentService.uploadDocument(filePart, contentLength, parentFolderId != null ? UUID.fromString(parentFolderId) : null, parseMetadata(metadataJson), allowDuplicateFileNames)
                .map(uploadResponse -> ResponseEntity.status(HttpStatus.CREATED).body(uploadResponse));
    }

    @PostMapping(value = "/upload-multiple", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload multiple documents",
            description = "Uploads multiple files, optionally with metadata and a parent folder ID.")
    public Flux<UploadResponse> uploadDocument(
            @RequestPart("file") Flux<FilePart> filePartFlux,
            @CustomJsonPart(value = "parametersByFilename")
            @Parameter(
                    description = ApiDescription.UPLOAD_MULTIPLE_DESCRIPTION,
                    schema = @Schema(type = "string", example = "[{\"filename\":\"file1.txt\",\"fileAttributes\":{\"metadata\":{\"country\": \"UK\", \"info\": {\"key\": \"Value\"}}}}, {\"filename\":\"file2.md\",\"fileAttributes\":{\"metadata\":{\"key1\": \"value1\"}}}]")
            )
            List<MultipleUploadFileParameter> parametersByFilename,
            @Parameter(description = ALLOW_DUPLICATE_FILE_NAME_PARAM_DESCRIPTION) @RequestParam(required = false, defaultValue = "false") Boolean allowDuplicateFileNames) {
        final Map<String, MultipleUploadFileParameterAttributes> parametersByFilenameMap = (parametersByFilename == null
                || parametersByFilename.isEmpty() ? Collections.emptyMap()
                : parametersByFilename.stream().collect(Collectors.toMap(MultipleUploadFileParameter::filename, MultipleUploadFileParameter::fileAttributes)));

        return filePartFlux.flatMapSequential(filePart -> {
                    MultipleUploadFileParameterAttributes fileParameters = parametersByFilenameMap.get(filePart.filename());

                    if(fileParameters == null) {
                        log.debug("Processing file: {}", filePart.filename());
                        return documentService.uploadDocument(filePart,  null, null, null, allowDuplicateFileNames);
                    }
                    log.debug("Processing file: {}, parentId: {}, metadata: {}",
                            filePart.filename(),
                            fileParameters.parentFolderId(),
                            fileParameters.metadata());

                    return documentService.uploadDocument(filePart,  null, fileParameters.parentFolderId(), fileParameters.metadata(), allowDuplicateFileNames);
                })
                .doOnComplete(() -> log.info("Finished processing all files for /upload-multiple"))
                .doOnError(error -> log.error("Error during /upload-multiple processing stream: {}", error.getMessage(), error));
    }

    private Map<String, Object> parseMetadata(String metadataJson) {
        try {
            if (metadataJson == null || metadataJson.isBlank()) {
                return Collections.emptyMap();
            }
            return objectMapper.readValue(metadataJson, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("Could not parse metadata JSON string: {}", metadataJson, e);
            // Decide on error handling: throw, or return empty, or a specific error metadata
            throw new IllegalArgumentException("Invalid metadata JSON format.", e);
        }
    }


    @PutMapping(value = "/{documentId}/replace-content", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Replace document content", description = "Replaces the content of an existing file document.")
    public Mono<ResponseEntity<ElementInfo>> replaceDocumentContent(
            @PathVariable UUID documentId,
            @RequestPart("file") Mono<FilePart> newFilePartMono,
            @Parameter(hidden = true) @RequestHeader(name = "Content-Length", required = false) Long contentLength) {
        return newFilePartMono.flatMap(filePart -> documentService.replaceDocumentContent(documentId, filePart, contentLength))
                .map(doc -> new ElementInfo(doc.getId(), doc.getName(), doc.getType().name()))
                .map(ResponseEntity::ok);
    }

    @PutMapping("/{documentId}/replace-metadata")
    @Operation(summary = "Replace document metadata", description = "Replaces all metadata of a document (file or folder).")
    public Mono<ResponseEntity<ElementInfo>> replaceDocumentMetadata(
            @Parameter(name = "documentId") @PathVariable UUID documentId,
            @RequestBody(description = "New metadata map. Replaces all existing metadata.", required = true,
                    content = @Content(schema = @Schema(type = "object", additionalProperties = Schema.AdditionalPropertiesValue.TRUE)))
            @org.springframework.web.bind.annotation.RequestBody Map<String, Object> newMetadata) {
        return documentService.replaceDocumentMetadata(documentId, newMetadata)
                .map(doc -> new ElementInfo(doc.getId(), doc.getName(), doc.getType().name()))
                .map(ResponseEntity::ok);
    }


    @PatchMapping("/{documentId}/metadata")
    @Operation(summary = "Update document metadata", description = "Updates or adds specific metadata fields for a document.")
    public Mono<ResponseEntity<ElementInfo>> updateDocumentMetadata(
            @PathVariable UUID documentId,
            @Valid @org.springframework.web.bind.annotation.RequestBody UpdateMetadataRequest request) {
        return documentService.updateDocumentMetadata(documentId, request)
                .map(doc -> new ElementInfo(doc.getId(), doc.getName(), doc.getType().name()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping("/{documentId}/metadata")
    @Operation(summary = "Delete specific metadata keys", description = "Deletes specified metadata keys from a document.")
    public Mono<ResponseEntity<Void>> deleteDocumentMetadata(
            @PathVariable UUID documentId,
            @Valid @org.springframework.web.bind.annotation.RequestBody DeleteMetadataRequest request) {
        return documentService.deleteDocumentMetadata(documentId, request)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/{documentId}/download")
    @Operation(summary = "Download a document", description = "Downloads a single file document.")
    public Mono<ResponseEntity<Resource>> downloadDocument(@PathVariable UUID documentId) {
        return documentService.findDocumentToDownloadById(documentId) // First get metadata like name
                .flatMap(docInfo -> documentService.downloadDocument(docInfo)
                        .map(resource -> sendDownloadResponse(docInfo, resource))
                );
    }

    private ResponseEntity<Resource> sendDownloadResponse(Document document, Resource resource) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + document.getName() + (document.getType() == FILE ? "" : ZIP) + "\"")
                .contentType(document.getType() == FILE && document.getContentType() != null ? MediaType.parseMediaType(document.getContentType()) : MediaType.APPLICATION_OCTET_STREAM)
                .body(resource);
    }

    @PostMapping("/download-multiple")
    @Operation(summary = "Download multiple documents as ZIP", description = "Downloads multiple documents as a single ZIP file.")
    public Mono<ResponseEntity<Resource>> downloadMultipleDocumentsAsZip(
            @org.springframework.web.bind.annotation.RequestBody List<UUID> documentIds) {
        return documentService.downloadMultipleDocumentsAsZip(documentIds)
                .map(resource -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, ATTACHMENT_ZIP)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .body(resource));
    }

    @PostMapping("/search/ids-by-metadata")
    @Operation(summary = "Search document IDs by metadata", description = "Finds document IDs matching all provided metadata criteria.")
    public Flux<UUID> searchDocumentIdsByMetadata(
            @Valid @org.springframework.web.bind.annotation.RequestBody SearchByMetadataRequest request) {
        return documentService.searchDocumentIdsByMetadata(request);
    }

    @PostMapping("/{documentId}/search/metadata") // POST to allow body for keys
    @Operation(summary = "Search metadata of a document", description = "Retrieves metadata for a document. Can filter by keys.")
    public Mono<ResponseEntity<Map<String, Object>>> getDocumentMetadata(
            @PathVariable UUID documentId,
            @org.springframework.web.bind.annotation.RequestBody(required = false) SearchMetadataRequest request) {
        SearchMetadataRequest actualRequest = (request == null) ? new SearchMetadataRequest(Collections.emptyList()) : request;
        return documentService.getDocumentMetadata(documentId, actualRequest)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/{documentId}/info") // GET document info
    @Operation(summary = "Get information of a document", description = "Retrieves information for a document.")
    public Mono<ResponseEntity<DocumentInfo>> getDocumentInfo(
            @PathVariable UUID documentId,
            @Parameter(description = "if false : only name, type and parentId are sent (when not null) - if true : metadata and size are added in the response") @RequestParam(required = false) Boolean withMetadata) {
        return documentService.getDocumentInfo(documentId, withMetadata)
                .map(ResponseEntity::ok);
    }
}