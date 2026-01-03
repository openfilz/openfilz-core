package org.openfilz.dms.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Set;

/**
 * Configuration properties for thumbnail generation feature.
 * Maps to openfilz.thumbnail.* properties in application.yml
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "openfilz.thumbnail")
public class ThumbnailProperties {

    /**
     * Enable or disable thumbnail generation.
     */
    private boolean active = false;

    /**
     * Generation mode: local (in-process) or redis (distributed via Pub/Sub).
     */
    private String generationMode = "local";

    /**
     * ImgProxy configuration (for image thumbnails).
     */
    private ImgProxy imgproxy = new ImgProxy();

    /**
     * Gotenberg configuration (for PDF and Office document thumbnails).
     */
    private Gotenberg gotenberg = new Gotenberg();

    /**
     * mTLS configuration for ImgProxy to access document source endpoint.
     */
    private MtlsAccess mtlsAccess = new MtlsAccess();

    /**
     * Thumbnail storage configuration.
     */
    private Storage storage = new Storage();

    /**
     * Redis configuration for distributed thumbnail generation.
     */
    private Redis redis = new Redis();

    /**
     * Thumbnail dimensions.
     */
    private Dimensions dimensions = new Dimensions();

    /**
     * Content types supported for thumbnail generation.
     * Images are processed by ImgProxy, PDFs and Office documents by Gotenberg.
     */
    private List<String> supportedContentTypes = List.of(
            // Images (ImgProxy)
            "image/jpeg",
            "image/png",
            "image/gif",
            "image/webp",
            "image/bmp",
            "image/tiff",
            // PDF (Gotenberg)
            "application/pdf",
            // Microsoft Office (Gotenberg)
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            // OpenDocument (Gotenberg)
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
    );

    /**
     * Content types that should use Gotenberg (PDFs and Office documents).
     */
    private static final Set<String> GOTENBERG_CONTENT_TYPES = Set.of(
            "application/pdf",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation"
    );

    /**
     * ImgProxy server configuration (for images).
     */
    @Data
    public static class ImgProxy {
        /**
         * URL of the ImgProxy server.
         */
        private String url = "http://imgproxy:8080";

        /**
         * mTLS configuration for outbound requests to ImgProxy.
         */
        private Mtls mtls = new Mtls();

        @Data
        public static class Mtls {
            private boolean enabled = false;
            private String keystorePath;
            private String keystorePassword;
            private String truststorePath;
            private String truststorePassword;
        }
    }

    /**
     * Gotenberg server configuration (for PDFs and Office documents).
     */
    @Data
    public static class Gotenberg {
        /**
         * URL of the Gotenberg server.
         */
        private String url = "http://gotenberg:3000";

        /**
         * Timeout for Gotenberg requests in seconds.
         */
        private int timeoutSeconds = 60;
    }

    /**
     * mTLS configuration for inbound requests from ImgProxy.
     */
    @Data
    public static class MtlsAccess {
        /**
         * Enable mTLS authentication for ImgProxy access.
         */
        private boolean enabled = false;

        /**
         * Path to truststore containing ImgProxy's CA certificate.
         */
        private String truststorePath;

        /**
         * Password for the truststore.
         */
        private String truststorePassword;

        /**
         * DN pattern to validate ImgProxy client certificates.
         * Example: "CN=imgproxy.*"
         */
        private String allowedDnPattern = "CN=imgproxy.*";
    }

    /**
     * Thumbnail storage configuration.
     */
    @Data
    public static class Storage {
        /**
         * If true, use the same storage type as main document storage.
         * If false, use separate configuration below.
         */
        private boolean useMainStorage = true;

        /**
         * Storage type when useMainStorage is false.
         * Values: local, minio
         */
        private String type = "local";

        /**
         * Local storage configuration.
         */
        private Local local = new Local();

        /**
         * MinIO storage configuration.
         */
        private Minio minio = new Minio();

        @Data
        public static class Local {
            /**
             * Base path for local thumbnail storage.
             * If not set, defaults to {storage.local.base-path}/thumbnails
             */
            private String basePath;
        }

        @Data
        public static class Minio {
            /**
             * MinIO endpoint URL.
             * If not set, uses main storage endpoint.
             */
            private String endpoint;

            /**
             * MinIO access key.
             * If not set, uses main storage credentials.
             */
            private String accessKey;

            /**
             * MinIO secret key.
             * If not set, uses main storage credentials.
             */
            private String secretKey;

            /**
             * Bucket name for thumbnails.
             * If not set, defaults to main bucket with /thumbnails prefix.
             */
            private String bucketName;
        }
    }

    /**
     * Redis configuration for distributed thumbnail generation.
     */
    @Data
    public static class Redis {
        /**
         * Redis channel name for thumbnail events.
         */
        private String channel = "openfilz:thumbnails";
    }

    /**
     * Thumbnail dimensions configuration.
     */
    @Data
    public static class Dimensions {
        /**
         * Thumbnail width in pixels.
         */
        private int width = 256;

        /**
         * Thumbnail height in pixels.
         */
        private int height = 256;
    }

    /**
     * Check if a content type is supported for thumbnail generation.
     */
    public boolean isContentTypeSupported(String contentType) {
        if (contentType == null || supportedContentTypes == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return supportedContentTypes.stream()
                .anyMatch(supported -> ct.startsWith(supported.toLowerCase()));
    }

    /**
     * Check if a content type should use Gotenberg (PDF or Office documents).
     */
    public boolean shouldUseGotenberg(String contentType) {
        if (contentType == null) {
            return false;
        }
        String ct = contentType.toLowerCase();
        return GOTENBERG_CONTENT_TYPES.stream()
                .anyMatch(gotenbergType -> ct.startsWith(gotenbergType.toLowerCase()));
    }

    /**
     * Check if a content type should use ImgProxy (images).
     */
    public boolean shouldUseImgProxy(String contentType) {
        return isContentTypeSupported(contentType) && !shouldUseGotenberg(contentType);
    }

    /**
     * Check if generation mode is Redis-based.
     */
    public boolean isRedisMode() {
        return "redis".equalsIgnoreCase(generationMode);
    }

    /**
     * Check if generation mode is local (in-process).
     */
    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(generationMode);
    }
}
