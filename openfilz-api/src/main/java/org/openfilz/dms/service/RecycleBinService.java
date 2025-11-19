package org.openfilz.dms.service;

import org.openfilz.dms.dto.response.FolderElementInfo;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing recycle bin operations
 */
public interface RecycleBinService {

    /**
     * List all deleted items for the current user (recycle bin contents)
     */
    Flux<FolderElementInfo> listDeletedItems();

    /**
     * Restore one or more items from the recycle bin
     * @param documentIds List of document IDs to restore
     */
    Mono<Void> restoreItems(List<UUID> documentIds);

    /**
     * Permanently delete items from the recycle bin
     * Physical files will be deleted from storage
     * @param documentIds List of document IDs to permanently delete
     */
    Mono<Void> permanentlyDeleteItems(List<UUID> documentIds);

    /**
     * Empty the entire recycle bin for the current user
     * Permanently deletes all items marked as deleted by this user
     */
    Mono<Void> emptyRecycleBin();

    /**
     * Count items in recycle bin for current user
     */
    Mono<Long> countDeletedItems();
}
