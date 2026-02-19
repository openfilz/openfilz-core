package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for MinIO/S3 storage.
 * Maps to storage.minio.* properties in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "storage.minio")
public class MinioProperties {

    /**
     * MinIO endpoint URL.
     */
    private String endpoint = "http://localhost:9000";

    /**
     * MinIO access key.
     */
    private String accessKey = "minioadmin";

    /**
     * MinIO secret key.
     */
    private String secretKey = "minioadmin";

    /**
     * Bucket name for document storage.
     */
    private String bucketName = "dms-bucket";

    /**
     * Bucket Versioning enabled status.
     */
    private boolean versioningEnabled = true;
}
