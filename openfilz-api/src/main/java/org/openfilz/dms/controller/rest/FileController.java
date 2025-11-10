package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CopyRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.RenameRequest;
import org.openfilz.dms.dto.response.CopyResponse;
import org.openfilz.dms.dto.response.ElementInfo;
import org.openfilz.dms.service.DocumentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(RestApiVersion.API_PREFIX + "/files")
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth")
public class FileController {

    private final DocumentService documentService;

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
