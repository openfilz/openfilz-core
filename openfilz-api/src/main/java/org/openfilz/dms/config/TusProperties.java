package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for TUS (resumable upload) protocol.
 * Maps to openfilz.tus.* properties in application.yml
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.tus")
public class TusProperties {

    /**
     * Whether TUS uploads are enabled.
     * Default: true
     */
    private boolean enabled = true;

    /**
     * Maximum upload size in bytes.
     * Default: 10GB (10737418240 bytes)
     */
    private long maxUploadSize = 10737418240L;

    /**
     * Recommended chunk size in bytes.
     * Default: 50MB (52428800 bytes) - safely under Cloudflare's 100MB limit
     */
    private long chunkSize = 52428800L;

    /**
     * Upload expiration period in milliseconds.
     * Incomplete uploads older than this will be cleaned up.
     * Default: 24 hours (86400000 ms)
     */
    private long uploadExpirationPeriod = 86400000L;

    /**
     * Cleanup interval in milliseconds for expired uploads.
     * Default: 1 hour (3600000 ms)
     */
    private long cleanupInterval = 3600000L;

    @PostConstruct
    public void validate() {
        if (maxUploadSize <= 0) {
            throw new IllegalArgumentException(
                    "openfilz.tus.max-upload-size must be > 0. Current value: " + maxUploadSize);
        }
        if (chunkSize <= 0) {
            throw new IllegalArgumentException(
                    "openfilz.tus.chunk-size must be > 0. Current value: " + chunkSize);
        }
        if (uploadExpirationPeriod <= 0) {
            throw new IllegalArgumentException(
                    "openfilz.tus.upload-expiration-period must be > 0. Current value: " + uploadExpirationPeriod);
        }

        if (enabled) {
            log.info("TUS resumable uploads enabled (using StorageService with _tus/ prefix)");
            log.info("  Max upload size: {} bytes ({} MB)", maxUploadSize, maxUploadSize / (1024 * 1024));
            log.info("  Chunk size: {} bytes ({} MB)", chunkSize, chunkSize / (1024 * 1024));
            log.info("  Upload expiration: {} ms ({} hours)", uploadExpirationPeriod, uploadExpirationPeriod / 3600000);
        } else {
            log.info("TUS resumable uploads disabled");
        }
    }

}
