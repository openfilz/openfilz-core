package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Configuration properties for OnlyOffice DocumentServer integration.
 * Maps to onlyoffice.* properties in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "onlyoffice")
public class OnlyOfficeProperties {

    /**
     * Enable or disable OnlyOffice integration.
     * If disabled, Office files will use the default viewer (mammoth.js/xlsx).
     */
    private boolean enabled = false;

    /**
     * DocumentServer configuration.
     */
    private DocumentServer documentServer = new DocumentServer();

    /**
     * JWT configuration for OnlyOffice authentication.
     */
    private Jwt jwt = new Jwt();

    /**
     * List of supported file extensions for OnlyOffice editing.
     */
    private List<String> supportedExtensions;

    /**
     * DocumentServer connection settings.
     */
    @Data
    public static class DocumentServer {
        /**
         * URL of the OnlyOffice DocumentServer.
         * Example: https://documentserver.example.com
         */
        private String url = "https://localhost";

        /**
         * Path to the OnlyOffice API JavaScript file.
         */
        private String apiPath = "/web-apps/apps/api/documents/api.js";

        /**
         * Hosts (or URLs, only the host is kept) the API may download a saved document from when
         * the document server's callback hands back a download URL, in addition to the host of
         * {@link #url}. Needed only when the document server names itself differently towards
         * the API than towards the browser (split or internally proxied setups). Any other host
         * in a callback is refused: the callback endpoint is reachable with a user-held token,
         * and the downloaded response becomes the document's content.
         */
        private List<String> allowedDownloadHosts = new ArrayList<>();

        /**
         * A save download that stays silent for this long is abandoned.
         */
        private Duration downloadTimeout = Duration.ofSeconds(120);

        /**
         * A document the document server hands back above this many bytes is refused (the per-file
         * storage quota still applies afterwards). 0 = no cap here.
         */
        private long maxDownloadBytes = 256L * 1024 * 1024;

        /**
         * Get the full URL to the OnlyOffice API JavaScript.
         */
        public String getApiUrl() {
            return url + apiPath;
        }
    }

    /**
     * JWT settings for secure communication with OnlyOffice.
     */
    @Data
    public static class Jwt {
        /**
         * Enable JWT authentication for OnlyOffice requests.
         */
        private boolean enabled = true;

        /**
         * Secret key for signing JWT tokens.
         * Must match the secret configured in OnlyOffice DocumentServer.
         */
        private String secret;

        /**
         * JWT token expiration time in seconds.
         * Default: 1 hour (3600 seconds).
         */
        private long expirationSeconds = 3600;
    }

    /**
     * Check if a file extension is supported by OnlyOffice.
     */
    public boolean isExtensionSupported(String extension) {
        if (extension == null || supportedExtensions == null) {
            return false;
        }
        String ext = extension.toLowerCase().replaceFirst("^\\.", "");
        return supportedExtensions.stream()
                .anyMatch(supported -> supported.equalsIgnoreCase(ext));
    }
}
