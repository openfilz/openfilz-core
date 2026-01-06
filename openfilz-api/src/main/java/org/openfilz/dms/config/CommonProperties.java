package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for thumbnail generation feature.
 * Maps to openfilz.thumbnail.* properties in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.common")
public class CommonProperties {

    /**
     * Base URL that OnlyOffice DocumentServer uses to reach OpenFilz API.
     * Use host.docker.internal when OnlyOffice run in Docker and OpenFilz runs on host.
     * Examples: http://openfilz-api:8081 or http://host.docker.internal:8081
     */
    private String apiInternalBaseUrl = "http://localhost:8081";

    private String apiPublicBaseUrl = "http://localhost:8081";

}
