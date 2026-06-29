package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.MinioProperties;
import org.openfilz.dms.service.StorageService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Periodically deletes noncurrent (old) object versions beyond the configured retention period.
 *
 * <p><b>Native-image-safe by design.</b> The bean is registered <i>unconditionally</i> — it does
 * NOT use {@code @ConditionalOnProperty}/{@code @Profile}, which are evaluated at AOT/build time in
 * a native image and would bake in the storage-type / versioning decision (so a single native
 * binary couldn't serve both local and minio deployments). Instead every criterion is checked at
 * <b>runtime</b> inside the scheduled method. The job does real work only when ALL hold:
 * <ul>
 *   <li>{@code storage.type = minio},</li>
 *   <li>{@code storage.minio.versioning-enabled = true},</li>
 *   <li>{@code openfilz.storage.version-cleanup.enabled = true} (off by default), and</li>
 *   <li>{@code openfilz.storage.version-cleanup.retention-days > 0} (0 = keep versions forever).</li>
 * </ul>
 * Otherwise it returns immediately (no-op). For non-minio storage the injected {@link StorageService}
 * is the filesystem impl whose {@code cleanupExpiredVersions} is a no-op anyway — the runtime guards
 * just avoid the call entirely.
 *
 * <p>Note: on a managed Scaleway bucket the same pruning can be done server-side via an
 * Object Storage lifecycle rule (set at provisioning time); this scheduler is the portable
 * mechanism for on-prem / BYO MinIO backends that have no lifecycle automation. Both are idempotent.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StorageVersionCleanupScheduler {

    private final StorageService storageService;
    private final MinioProperties minioProperties;

    @Value("${storage.type:local}")
    private String storageType;

    @Value("${openfilz.storage.version-cleanup.enabled:false}")
    private boolean cleanupEnabled;

    @Value("${openfilz.storage.version-cleanup.retention-days:30}")
    private int retentionDays;

    @Scheduled(cron = "${openfilz.storage.version-cleanup.cron:0 0 2 * * ?}")
    public void cleanupExpiredVersions() {
        if (!cleanupEnabled) {
            return;
        }
        if (!"minio".equalsIgnoreCase(storageType)) {
            log.debug("Version cleanup skipped: storage.type is not minio");
            return;
        }
        if (!minioProperties.isVersioningEnabled()) {
            log.debug("Version cleanup skipped: MinIO versioning is disabled");
            return;
        }
        if (retentionDays <= 0) {
            log.debug("Version cleanup skipped: retention-days={} (unlimited)", retentionDays);
            return;
        }

        log.info("Starting MinIO version retention cleanup (versions older than {} days)", retentionDays);
        storageService.cleanupExpiredVersions(Duration.ofDays(retentionDays))
                .doOnNext(count -> log.info("Version retention cleanup deleted {} version(s)", count))
                .doOnError(e -> log.error("Version retention cleanup failed", e))
                .subscribe();
    }
}
