package org.openfilz.dms.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code openfilz.branding.*}: the product brand every e-mail and generated page is rendered with.
 * Read per call by {@link org.openfilz.dms.service.branding.PropertiesBrandingProvider}, so a setter
 * call takes effect at once. Blank values fall back to the older per-mailer properties
 * ({@code openfilz.signature.mail.*}, {@code openfilz.workflows.mail.*}), then to OpenFilz.
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "openfilz.branding")
public class BrandingProperties {

    /** Product name shown in place of "OpenFilz". Blank = the legacy mailer value, else OpenFilz. */
    private String productName = "";

    /** Absolute https logo URL. Blank = the legacy mailer value, else the hosted OpenFilz logo; {@code none} = no image. */
    private String logoUrl = "";

    /** Main colour, {@code #rrggbb}. Blank = the OpenFilz palette. */
    private String primaryColor = "";

    /** Link of the brand in e-mail footers. Blank = openfilz.com for the OpenFilz brand, no link otherwise. */
    private String brandUrl = "";

    /** Help link shown in place of the OpenFilz help pages. Blank = none. */
    private String supportUrl = "";

    /** Show a "Powered by OpenFilz" line under a brand other than OpenFilz. */
    private boolean showPoweredBy = true;
}
