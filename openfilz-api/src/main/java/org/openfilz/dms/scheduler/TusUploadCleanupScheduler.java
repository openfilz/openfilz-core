package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.TusProperties;
import org.openfilz.dms.service.TusUploadService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduler that periodically cleans up expired TUS uploads.
 *
 * Incomplete uploads that have exceeded the expiration period are removed
 * to free up temporary storage space.
 */
@Slf4j
@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.tus.enabled", havingValue = "true", matchIfMissing = true)
public class TusUploadCleanupScheduler {

    private final TusUploadService tusUploadService;

    /**
     * Runs cleanup of expired uploads at the configured interval.
     * Default: every hour (3600000 ms).
     *
     * The interval is configured via openfilz.tus.cleanup-interval property.
     */
    @Scheduled(fixedDelayString = "${openfilz.tus.cleanup-interval:3600000}")
    public void cleanupExpiredUploads() {
        log.debug("Running TUS upload cleanup task");
        tusUploadService.cleanupExpiredUploads()
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.info("TUS upload cleanup completed: {} expired uploads removed", count);
                    } else {
                        log.debug("TUS upload cleanup completed: no expired uploads found");
                    }
                })
                .doOnError(e -> log.error("Error during TUS upload cleanup", e))
                .subscribe();
    }
}
