// com/example/dms/controller/FolderController.java
package org.openfilz.dms.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.ElementInfo;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.service.DocumentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@RestController
@RequestMapping(RestApiVersion.API_PREFIX + "/folders")
@RequiredArgsConstructor
@SecurityRequirement(name = "keycloak_auth") // For Swagger UI
public class FolderController {

    private final DocumentService documentService;

    @PostMapping
    @Operation(summary = "Create a new folder", description = "Creates a new folder, optionally under a parent folder.")
    public Mono<ResponseEntity<FolderResponse>> createFolder(@Valid @RequestBody CreateFolderRequest request, Authentication authentication) {
        return documentService.createFolder(request, authentication)
                .map(folderResponse -> ResponseEntity.status(HttpStatus.CREATED).body(folderResponse));
    }

    @PostMapping("/move")
    @Operation(summary = "Move folders", description = "Moves a set of folders (and their contents) into an existing target folder.")
    public Mono<ResponseEntity<Void>> moveFolders(@Valid @RequestBody MoveRequest request, Authentication authentication) {
        return documentService.moveFolders(request, authentication)
                .thenReturn(ResponseEntity.ok().build());
    }

    @PostMapping("/copy")
    @Operation(summary = "Copy folders", description = "Copies a set of folders (and their contents) into an existing target folder.")
    public Mono<ResponseEntity<Void>> copyFolders(@Valid @RequestBody CopyRequest request, Authentication authentication) {
        return documentService.copyFolders(request, authentication)
                .last().thenReturn(ResponseEntity.ok().build());
    }

    @PutMapping("/{folderId}/rename")
    @Operation(summary = "Rename a folder", description = "Renames an existing folder.")
    public Mono<ResponseEntity<ElementInfo>> renameFolder(@PathVariable UUID folderId, @Valid @RequestBody RenameRequest request, Authentication authentication) {
        return documentService.renameFolder(folderId, request, authentication)
                .map(doc -> new ElementInfo(doc.getId(), doc.getName(), doc.getType().name()))
                .map(ResponseEntity::ok);
    }

    @DeleteMapping
    @Operation(summary = "Delete folders", description = "Deletes a set of folders and their contents from storage and database.")
    public Mono<ResponseEntity<Void>> deleteFolders(@Valid @RequestBody DeleteRequest request, Authentication authentication) {
        return documentService.deleteFolders(request, authentication)
                .thenReturn(ResponseEntity.noContent().build());
    }

    @GetMapping("/list") // List all documents in a given folder
    @Operation(summary = "List files and subfolders contained in a given folder",
            description = "Retrieves document information of all files and subfolders contained in a given folder " +
                    "(no recursive list, just the flat list of objects at the root level of a folder)")
    public Flux<FolderElementInfo> listFolder(
            @RequestParam(required = false) @Parameter(description = "if null, empty or not provided, then lists the content of the root folder") UUID folderId,
            @RequestParam(required = false, defaultValue = "false") @Parameter(description = "if true, only files are listed") Boolean onlyFiles,
            @RequestParam(required = false, defaultValue = "false") @Parameter(description = "if true, only folders are listed") Boolean onlyFolders,
            Authentication authentication) {
        return documentService.listFolderInfo(folderId, onlyFiles, onlyFolders, authentication);
    }

    @Deprecated
    @GetMapping("/count") // List all documents in a given folder
    @Operation(summary = "Count files and subfolders contained in a given folder - return 0 if empty or not exists",
            description = "Retrieves the number of elements (files and folders) contained in a given folder - return 0 if empty or not exists" +
                    "(no recursive count, just count the flat list of objects at the root level of a folder)")
    public Mono<Long> countFolderElements(@RequestParam(required = false) @Parameter(description = "ID of the folder or to count elements at the root level") UUID folderId, Authentication authentication) {
        return documentService.countFolderElements(folderId, authentication);
    }
}
