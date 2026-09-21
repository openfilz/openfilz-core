package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.dto.request.UnzipRequest;
import org.openfilz.dms.dto.response.CopyResponse;
import org.openfilz.dms.dto.response.ElementInfo;
import org.openfilz.dms.dto.response.UnzipResponse;
import org.openfilz.dms.service.DocumentService;
import org.openfilz.dms.service.UnzipService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES)
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class FileController {

    private final DocumentService documentService;
    private final UnzipService unzipService;

    @PostMapping("/move")
    @Operation(summary = "Move files", description = "Moves a set of files into an existing target folder.")
    public Mono<ResponseEntity<Void>> moveFiles(@Valid @RequestBody MoveRequest request) {
        return documentService.moveFiles(request)
                .thenReturn(ResponseEntity.ok().build());
    }

    @PostMapping("/copy")
    @Operation(summary = "Copy files", description = "Copies a set of files into an existing target folder.")
    public Flux<CopyResponse> copyFiles(@Valid @RequestBody CopyRequest request) {
        return documentService.copyFiles(request);
    }

    @PostMapping("/{fileId}/unzip")
    @Operation(summary = "Extract a ZIP file",
            description = "Extracts a ZIP document server-side, recreating its folder tree. Destination: the root when "
                    + "targetRoot is true, else targetFolderId, else the folder containing the ZIP; with newFolderName, a new "
                    + "folder of that name is created there first. Folders that already exist are merged; files whose name "
                    + "already exists are skipped (unless allowDuplicateFileNames) and listed in the response, like any "
                    + "entry that could not be extracted. Archive-wide limits answer 413 (ZIP_TOO_MANY_ENTRIES, "
                    + "ZIP_TOO_LARGE, ZIP_BOMB); a document that is not a readable ZIP answers 422 (NOT_A_ZIP, ZIP_INVALID).")
    public Mono<UnzipResponse> unzipFile(
            @Parameter(description = "Id of the ZIP document") @PathVariable UUID fileId,
            @Valid @RequestBody(required = false) UnzipRequest request) {
        return unzipService.unzip(fileId, request != null ? request : new UnzipRequest(null, null, null, null));
    }

    @PutMapping("/{fileId}/rename")
    @Operation(summary = "Rename a file", description = "Renames an existing file.")
    public Mono<ResponseEntity<ElementInfo>> renameFile(@PathVariable UUID fileId, @Valid @RequestBody RenameRequest request) {
        return documentService.renameFile(fileId, request)
                .map(doc -> new ElementInfo(doc.getId(), doc.getName(), doc.getType().name()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping
    @Operation(summary = "Delete files", description = "Deletes a set of files from storage and database.")
    public Mono<ResponseEntity<Void>> deleteFiles(@Valid @RequestBody DeleteRequest request) {
        return documentService.deleteFiles(request)
                .thenReturn(ResponseEntity.noContent().build());
    }
}
