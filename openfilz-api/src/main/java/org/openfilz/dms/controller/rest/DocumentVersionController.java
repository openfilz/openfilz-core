package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.DocumentVersionInfo;
import org.openfilz.dms.dto.response.RestoreVersionResponse;
import org.openfilz.dms.service.DocumentVersionService;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Version operations on file documents (MinIO/S3 bucket versioning).
 * All endpoints answer HTTP 409 (VersioningDisabledException) when versioning is unavailable.
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class DocumentVersionController {

    private final DocumentVersionService documentVersionService;

    @GetMapping("/{documentId}/versions")
    @Operation(summary = "List document versions",
            description = "Lists all stored versions of a file document, newest first. Requires storage versioning to be enabled (otherwise HTTP 409).")
    public Flux<DocumentVersionInfo> listVersions(@PathVariable UUID documentId) {
        return documentVersionService.listVersions(documentId);
    }

    @GetMapping("/{documentId}/versions/{versionId}/download")
    @Operation(summary = "Download a specific document version",
            description = "Downloads the content of a specific stored version of a file document.")
    public Mono<ResponseEntity<Resource>> downloadVersion(@PathVariable UUID documentId, @PathVariable String versionId) {
        return documentVersionService.downloadVersion(documentId, versionId)
                .map(version -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + version.fileName() + "\"")
                        .contentType(version.contentType() != null ? MediaType.parseMediaType(version.contentType()) : MediaType.APPLICATION_OCTET_STREAM)
                        .body(version.resource()));
    }

    @PostMapping("/{documentId}/versions/{versionId}/restore")
    @Operation(summary = "Restore a previous document version",
            description = "Restores the selected version as the new latest version. History-preserving: a new version is created on top, " +
                    "no version is deleted, and a RESTORE_DOCUMENT_VERSION audit entry is logged. Restoring the current latest version is rejected (HTTP 400).")
    public Mono<ResponseEntity<RestoreVersionResponse>> restoreVersion(@PathVariable UUID documentId, @PathVariable String versionId) {
        return documentVersionService.restoreVersion(documentId, versionId)
                .map(ResponseEntity::ok);
    }
}
