package org.openfilz.dms.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for OpenFilz quota settings.
 * Maps to openfilz.quota.* properties in application.yml
 */
@Slf4j
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.quota")
public class QuotaProperties {

    /**
     * Maximum file size for each uploaded file in megabytes (MB).
     * - If 0: No file size limit (default behavior)
     * - If > 0: Maximum file size in MB for each uploaded file
     * - If < 0: Invalid configuration (throws error at startup)
     */
    private Integer fileUpload = 0;

    /**
     * Maximum total storage quota per user in megabytes (MB).
     * - If 0: No user quota limit (default behavior)
     * - If > 0: Maximum total storage in MB for all files created by a user
     * - If < 0: Invalid configuration (throws error at startup)
     */
    private Integer user = 0;

    @PostConstruct
    public void validate() {
        if (fileUpload < 0) {
            throw new IllegalArgumentException(
                    "openfilz.quota.file-upload must be >= 0 (0 means no limit, > 0 means max file size in MB). Current value: " + fileUpload);
        }
        if (user < 0) {
            throw new IllegalArgumentException(
                    "openfilz.quota.user must be >= 0 (0 means no limit, > 0 means max total storage per user in MB). Current value: " + user);
        }

        if (fileUpload == 0) {
            log.info("File upload quota is disabled (no size limit per file)");
        } else {
            log.info("File upload quota is set to {} MB per file", fileUpload);
        }

        if (user == 0) {
            log.info("User storage quota is disabled (no total storage limit per user)");
        } else {
            log.info("User storage quota is set to {} MB per user", user);
        }
    }

    /**
     * Returns the file upload quota in bytes, or null if quota is disabled (0).
     */
    public Long getFileUploadQuotaInBytes() {
        if (fileUpload == null || fileUpload == 0) {
            return null;
        }
        return fileUpload * 1024L * 1024L;
    }

    /**
     * Returns the user quota in bytes, or null if quota is disabled (0).
     */
    public Long getUserQuotaInBytes() {
        if (user == null || user == 0) {
            return null;
        }
        return user * 1024L * 1024L;
    }

    /**
     * Checks if file upload quota enforcement is enabled.
     */
    public boolean isFileUploadQuotaEnabled() {
        return fileUpload != null && fileUpload > 0;
    }

    /**
     * Checks if user quota enforcement is enabled.
     */
    public boolean isUserQuotaEnabled() {
        return user != null && user > 0;
    }
}
