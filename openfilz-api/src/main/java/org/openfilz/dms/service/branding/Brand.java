package org.openfilz.dms.service.branding;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The product brand: what every e-mail and generated page shows in place of OpenFilz.
 * Every field is already resolved (never null); {@link #of} normalises raw values.
 *
 * @param productName   product name ("OpenFilz" by default)
 * @param logoUrl       absolute logo URL; blank = the hosted OpenFilz logo, {@code none} = no image
 * @param primaryColor  {@code #rrggbb}, lower case ({@link #DEFAULT_PRIMARY_COLOR} by default)
 * @param brandUrl      footer link; blank = no link
 * @param supportUrl    help link; blank = none
 * @param showPoweredBy show "Powered by OpenFilz" under a brand other than OpenFilz
 */
public record Brand(String productName, String logoUrl, String primaryColor, String brandUrl, String supportUrl,
                    boolean showPoweredBy) {

    public static final String DEFAULT_PRODUCT_NAME = "OpenFilz";
    public static final String DEFAULT_PRIMARY_COLOR = "#667eea";
    public static final String OPENFILZ_URL = "https://www.openfilz.com";

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    /** The OpenFilz brand. */
    public static final Brand OPENFILZ = new Brand(DEFAULT_PRODUCT_NAME, "", DEFAULT_PRIMARY_COLOR, OPENFILZ_URL, "", true);

    /** Builds a brand from raw values: blanks take the OpenFilz defaults, an invalid colour is ignored. */
    public static Brand of(String productName, String logoUrl, String primaryColor, String brandUrl,
                           String supportUrl, boolean showPoweredBy) {
        String name = blank(productName) ? DEFAULT_PRODUCT_NAME : productName.strip();
        boolean openfilz = DEFAULT_PRODUCT_NAME.equals(name);
        String url = blank(brandUrl) ? (openfilz ? OPENFILZ_URL : "") : brandUrl.strip();
        return new Brand(name, trim(logoUrl), normaliseColor(primaryColor), url, trim(supportUrl), showPoweredBy);
    }

    /** True when the product name is OpenFilz (the "Powered by" line is then never shown). */
    public boolean isOpenFilz() {
        return DEFAULT_PRODUCT_NAME.equals(productName);
    }

    /** Whether a "Powered by OpenFilz" line goes under this brand. */
    public boolean poweredByShown() {
        return showPoweredBy && !isOpenFilz();
    }

    /** A copy with another product name, same rest. */
    public Brand withProductName(String name) {
        return of(name, logoUrl, primaryColor, brandUrl, supportUrl, showPoweredBy);
    }

    /** A copy with another logo URL, same rest. */
    public Brand withLogoUrl(String url) {
        return new Brand(productName, trim(url), primaryColor, brandUrl, supportUrl, showPoweredBy);
    }

    /** {@code #rrggbb} in lower case, or the default colour for a blank or malformed value. */
    public static String normaliseColor(String color) {
        return isHexColor(color) ? color.strip().toLowerCase(Locale.ROOT) : DEFAULT_PRIMARY_COLOR;
    }

    public static boolean isHexColor(String color) {
        return color != null && HEX_COLOR.matcher(color.strip()).matches();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String trim(String s) {
        return s == null ? "" : s.strip();
    }
}
