package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.RecycleBinProperties;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.service.AuditService;
import org.openfilz.dms.service.StorageService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
@ConditionalOnProperty(prefix = "openfilz.recycle-bin", name = "enabled", havingValue = "true", matchIfMissing = true)
public class RecycleBinCleanupScheduler {

    private final DocumentDAO documentDAO;
    private final StorageService storageService;
    private final AuditService auditService;
    private final RecycleBinProperties recycleBinProperties;

    /**
     * Scheduled task that permanently deletes expired items from the recycle bin
     * Items are considered expired if they have been in the recycle bin longer than configured days
     */
    @Scheduled(cron = "${openfilz.recycle-bin.cleanup-cron:0 0 2 * * ?}")
    public void cleanupExpiredItems() {
        if (!recycleBinProperties.isEnabled()) {
            log.debug("Recycle bin is disabled, skipping cleanup");
            return;
        }

        int days = recycleBinProperties.getAutoCleanupDays();
        log.info("Starting recycle bin cleanup for items older than {} days", days);

        documentDAO.findExpiredDeletedDocuments(days)
                .flatMap(this::permanentlyDeleteDocumentRecursive)
                .doOnComplete(() -> log.info("Recycle bin cleanup completed"))
                .doOnError(e -> log.error("Error during recycle bin cleanup", e))
                .subscribe(); // Non-blocking subscription
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
                    .then(documentDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .doOnSuccess(v -> log.debug("Permanently deleted file: {}", docId));
        } else {
            // For folders, recursively delete all children first
            return documentDAO.findDocumentsByParentIdAndType(docId, null)
                    .flatMap(this::permanentlyDeleteDocumentRecursive)
                    .then(documentDAO.permanentDelete(docId))
                    .then(auditService.logAction(action, type, docId))
                    .doOnSuccess(v -> log.debug("Permanently deleted folder: {}", docId));
        }
    }
}
