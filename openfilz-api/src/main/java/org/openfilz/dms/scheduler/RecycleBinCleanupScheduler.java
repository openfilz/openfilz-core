package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.impl.DocumentSoftDeleteDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.MetadataPostProcessor;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.enums.DocumentType.FILE;

/**
 * Scheduler for automatic cleanup of expired items from the recycle bin
 * Runs on a configurable cron schedule (default: daily at 2 AM)
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true"),
        @ConditionalOnProperty(prefix = "openfilz.soft-delete.recycle-bin", name = "enabled", havingValue = "true")
})
public class RecycleBinCleanupScheduler {

    private final DocumentDAO documentDAO;
    private final DocumentSoftDeleteDAO documentSoftDeleteDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final RecycleBinProperties recycleBinProperties;
    private final TransactionalOperator tx;
    private final MetadataPostProcessor metadataPostProcessor;

    /**
     * Scheduled task that permanently deletes expired items from the recycle bin
     * Items are considered expired if they have been in the recycle bin longer than configured days
     */
    @Scheduled(cron = "${openfilz.soft-delete.recycle-bin.cleanup-cron:0 0 2 * * ?}")
    public void cleanupExpiredItems() {
        if (!recycleBinProperties.isEnabled()) {
            log.debug("Recycle bin is disabled, skipping cleanup");
            return;
        }

        String interval = recycleBinProperties.getAutoCleanupInterval();
        log.info("Starting recycle bin cleanup for items older than {}", interval);

        // Cache the stream to avoid re-querying the database.
        // The first subscriber will trigger the query, and subsequent subscribers will get the cached results.
        Flux<Document> documentsToDelete = documentSoftDeleteDAO.findExpiredDeletedDocuments(interval).cache();

        // Main processing pipeline
        documentsToDelete
                .flatMap(this::permanentlyDeleteDocumentRecursive)
                .then(
                        // After the deletions are complete, start a new Mono to decide the next step.
                        documentsToDelete.hasElements() // Returns a Mono<Boolean>
                                .flatMap(hasElements -> {
                                    if (hasElements) {
                                        // If the stream had elements, update the statistics.
                                        log.info("Expired documents were processed, updating statistics.");
                                        return documentSoftDeleteDAO.updateStatistics();
                                    } else {
                                        // If the stream was empty, do nothing and just complete.
                                        log.info("No expired documents to process, skipping statistics update.");
                                        return Mono.empty(); // Mono.empty() signals completion without any action.
                                    }
                                })
                )
                .doOnSuccess(_ -> log.info("Recycle bin cleanup completed successfully."))
                .doOnError(e -> log.error("Error during recycle bin cleanup", e))
                .subscribe();
    }

    /**
     * Recursively and permanently delete a document and all its children
     * For files: delete physical file and database record
     * For folders: recursively delete all children first, then delete folder
     */
    private Mono<Void> permanentlyDeleteDocumentRecursive(Document document) {
        UUID docId = document.getId();
        DocumentType type = document.getType();
        AuditAction action = type == FILE
                ? AuditAction.PERMANENT_DELETE_FILE
                : AuditAction.PERMANENT_DELETE_FOLDER;

        if (type == FILE) {
            // Delete physical file and database record
            return storageService.deleteFile(document.getStoragePath())
                    .onErrorResume(e -> {
                        log.warn("Failed to delete physical file for document {}: {}", docId, e.getMessage());
                        return Mono.empty(); // Continue even if physical file deletion fails
                    })
                    .then(documentSoftDeleteDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .doOnSuccess(_ -> {
                        log.debug("Permanently deleted file: {}", docId);
                        metadataPostProcessor.deleteDocument(docId);
                    });
        } else {
            // For folders, recursively delete all children first
            return documentDAO.findDocumentsByParentId(docId)
                    .flatMap(this::permanentlyDeleteDocumentRecursive)
                    .then(documentSoftDeleteDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .as(tx::transactional)
                    .doOnSuccess(_ -> {
                        log.debug("Permanently deleted folder: {}", docId);
                        metadataPostProcessor.deleteDocument(docId);
                    });
        }
    }
}
