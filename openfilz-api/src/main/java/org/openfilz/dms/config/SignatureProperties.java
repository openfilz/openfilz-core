package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Tunables for the e-Sign feature ({@code openfilz.signature.*}). {@link #active} is a
 * <b>runtime</b> toggle (read per request / per tick, never a bean condition) so a single
 * native image serves both enabled and disabled deployments.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.signature")
public class SignatureProperties {

    /** Master switch. Off: controllers answer 404, the public chain is inert, the sweeper idles. */
    private boolean active = false;

    /** Default envelope TTL in days when the create request omits it. */
    private int defaultExpiryDays = 30;

    /**
     * Public web base URL used to build the signer link sent by email ({base}sign?token=...).
     * Falls back to {@code openfilz.common.web-public-base-url}.
     */
    private String webBaseUrl = "";

    /** Max size in bytes of a base64 field image (signature / initials / image / stamp). */
    private int maxImageBytes = 512 * 1024;

    private final Otp otp = new Otp();
    private final Seal seal = new Seal();
    private final Mail mail = new Mail();

    @Getter
    @Setter
    public static class Otp {
        /** Code length (digits). */
        private int length = 6;
        /** Validity window in minutes. */
        private int validMinutes = 10;
        /** Failed attempts before a new code must be requested. */
        private int maxAttempts = 5;
    }

    @Getter
    @Setter
    public static class Seal {
        /**
         * {@code self-signed-dev} (default) | {@code pkcs12} | {@code openfilz-cloud}. Enterprise
         * registers its archiving-api sealer as primary regardless of this value and only uses it
         * as the fallback provider.
         */
        private String provider = "self-signed-dev";

        /** PKCS#12 keystore holding the seal key + cert ({@code pkcs12} provider). */
        private String keystorePath = "";
        private String keystorePassword = "";
        private String keystoreAlias = "openfilz-seal";

        /** Visible signature name / reason written into the PDF signature dictionary. */
        private String name = "OpenFilz e-Sign Seal";

        private final Cloud cloud = new Cloud();

        @Getter
        @Setter
        public static class Cloud {
            /** sign.openfilz.com base URL. */
            private String url = "https://sign.openfilz.com";
            /** Tenant API key (provisioned with a sign.openfilz.com account). */
            private String apiKey = "";
            private Duration timeout = Duration.ofSeconds(15);
        }
    }

    @Getter
    @Setter
    public static class Mail {
        /** From address of every e-Sign email. */
        private String from = "no-reply@openfilz.com";
        /** Display name in the From header. */
        private String fromName = "OpenFilz e-Sign";
        /** Product name used in subjects / bodies (EE white-label overrides this). */
        private String productName = "OpenFilz";
        /** Optional logo URL rendered at the top of the HTML emails. */
        private String logoUrl = "";
    }
}
