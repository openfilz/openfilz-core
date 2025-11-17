package org.openfilz.dms.controller.rest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.service.RecycleBinService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * REST Controller for recycle bin operations
 */
@Slf4j
@RestController
@RequestMapping(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN)
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true")
@Tag(name = "Recycle Bin", description = "Recycle bin management APIs (openfilz.soft-delete.active must be set to 'true')")
public class RecycleBinController {

    private final RecycleBinService recycleBinService;

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "List deleted items", description = "Get all items in the recycle bin for the current user")
    public Flux<FolderElementInfo> listDeletedItems() {
        log.info("Listing deleted items in recycle bin");
        return recycleBinService.listDeletedItems();
    }

    @GetMapping(value = "/count", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Count deleted items", description = "Get count of items in recycle bin")
    public Mono<Long> countDeletedItems() {
        log.info("Counting deleted items in recycle bin");
        return recycleBinService.countDeletedItems();
    }

    @PostMapping(value = "/restore", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Restore items", description = "Restore one or more items from the recycle bin")
    public Mono<Void> restoreItems(@RequestBody DeleteRequest request) {
        log.info("Restoring items from recycle bin: {}", request.documentIds());
        return recycleBinService.restoreItems(request.documentIds());
    }

    @DeleteMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently delete items", description = "Permanently delete items from recycle bin (cannot be undone)")
    public Mono<Void> permanentlyDeleteItems(@RequestBody DeleteRequest request) {
        log.info("Permanently deleting items from recycle bin: {}", request.documentIds());
        return recycleBinService.permanentlyDeleteItems(request.documentIds());
    }

    @DeleteMapping("/empty")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Empty recycle bin", description = "Permanently delete all items from recycle bin for current user")
    public Mono<Void> emptyRecycleBin() {
        log.info("Emptying recycle bin");
        return recycleBinService.emptyRecycleBin();
    }
}
