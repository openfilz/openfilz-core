package org.openfilz.dms.service.branding;

import java.util.Locale;

/**
 * Colour arithmetic for a brand colour: the darker and lighter shades derived from it and the WCAG
 * contrast ratio used to refuse a colour on which white text would be unreadable.
 */
public final class BrandColors {

    /** Lowest contrast accepted between white text and the brand colour (WCAG AA for large text and UI parts). */
    public static final double MIN_CONTRAST_WITH_WHITE = 3.0;

    private BrandColors() {
    }

    /** The colour mixed with black by {@code amount} (0 to 1). */
    public static String darker(String hex, double amount) {
        int[] c = rgb(hex);
        return toHex((int) Math.round(c[0] * (1 - amount)), (int) Math.round(c[1] * (1 - amount)),
                (int) Math.round(c[2] * (1 - amount)));
    }

    /** The colour mixed with white by {@code amount} (0 to 1). */
    public static String lighter(String hex, double amount) {
        int[] c = rgb(hex);
        return toHex((int) Math.round(c[0] + (255 - c[0]) * amount), (int) Math.round(c[1] + (255 - c[1]) * amount),
                (int) Math.round(c[2] + (255 - c[2]) * amount));
    }

    /** WCAG 2 contrast ratio between two {@code #rrggbb} colours (1 to 21). */
    public static double contrast(String a, String b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Contrast of white text on the colour. */
    public static double contrastWithWhite(String hex) {
        return contrast(hex, "#ffffff");
    }

    private static double luminance(String hex) {
        int[] c = rgb(hex);
        return 0.2126 * channel(c[0]) + 0.7152 * channel(c[1]) + 0.0722 * channel(c[2]);
    }

    private static double channel(int v) {
        double s = v / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }

    private static int[] rgb(String hex) {
        if (!Brand.isHexColor(hex)) {
            throw new IllegalArgumentException("expected a colour #rrggbb");
        }
        String h = hex.strip().substring(1);
        return new int[]{Integer.parseInt(h.substring(0, 2), 16), Integer.parseInt(h.substring(2, 4), 16),
                Integer.parseInt(h.substring(4, 6), 16)};
    }

    private static String toHex(int r, int g, int b) {
        return String.format(Locale.ROOT, "#%02x%02x%02x", clamp(r), clamp(g), clamp(b));
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
