package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for recycle bin functionality.
 * Maps to openfilz.soft-delete.recycle-bin properties in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.soft-delete.recycle-bin")
public class RecycleBinProperties {

    /**
     * Enable or disable recycle bin functionality.
     * If disabled, delete operations will perform hard delete.
     */
    private boolean enabled = true;

    /**
     * Number of days before items in recycle bin are automatically deleted.
     * Set to 0 to disable auto-cleanup.
     */
    private int autoCleanupDays = 30;

    /**
     * Cron expression for the cleanup scheduler.
     * Default: "0 0 2 * * ?" (daily at 2 AM)
     */
    private String cleanupCron = "0 0 2 * * ?";
}
