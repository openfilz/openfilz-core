package org.openfilz.dms.utils;

import org.springframework.web.util.HtmlUtils;

/**
 * The one HTML shell every OpenFilz e-mail is rendered in: a gradient header carrying the logo
 * and the title, a white content card, and a footer with the brand. Same look as the Keycloak
 * e-mail theme ({@code themes/openfilz/email/html/template.ftl}), so account mails and product
 * mails read as one family.
 *
 * <p>Table-based, inline-styled markup only — the subset Gmail, Outlook (Word engine) and Apple
 * Mail all render. Gradients carry a solid {@code bgcolor} fallback for Outlook. Callers pass
 * already-localised, already-escaped fragments; {@link #esc(String)} is there for user input.
 */
public final class EmailLayout {

    /** Hosted OpenFilz logo used when a deployment configures none. */
    public static final String DEFAULT_LOGO_URL = "https://www.openfilz.com/assets/img/logo-email-header.png";
    public static final String BRAND_URL = "https://www.openfilz.com";

    private static final String FONT = "-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'Helvetica Neue',Arial,sans-serif";
    private static final String PRIMARY = "#667eea";
    private static final String GRADIENT = "linear-gradient(135deg,#667eea 0%,#764ba2 100%)";

    private EmailLayout() {
    }

    /**
     * Renders a complete e-mail.
     *
     * @param lang       language tag for the {@code lang} attribute (may be null)
     * @param rtl        right-to-left script (Arabic)
     * @param title      header title (localised, escaped)
     * @param content    body fragments built with the helpers of this class
     * @param footerNote small print above the brand (localised, escaped; may be blank)
     * @param brandName  product name shown in the footer (white label; blank = OpenFilz)
     * @param logoUrl    absolute logo URL (blank = {@link #DEFAULT_LOGO_URL})
     */
    public static String page(String lang, boolean rtl, String title, String content,
                              String footerNote, String brandName, String logoUrl) {
        String dir = rtl ? "rtl" : "ltr";
        String align = rtl ? "right" : "left";
        String logo = isBlank(logoUrl) ? DEFAULT_LOGO_URL : logoUrl;
        String brand = esc(isBlank(brandName) ? "OpenFilz" : brandName);
        return "<!DOCTYPE html><html lang=\"" + esc(isBlank(lang) ? "en" : lang) + "\" dir=\"" + dir + "\">"
                + "<head><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1.0\">"
                + "<meta name=\"color-scheme\" content=\"light only\"><title>" + title + "</title></head>"
                + "<body style=\"margin:0;padding:0;background-color:#f4f6f9;font-family:" + FONT + ";\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" bgcolor=\"#f4f6f9\" style=\"background-color:#f4f6f9;\">"
                + "<tr><td style=\"padding:40px 16px;\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" dir=\"" + dir + "\" "
                + "style=\"max-width:600px;margin:0 auto;background-color:#ffffff;border-radius:16px;box-shadow:0 4px 24px rgba(0,0,0,0.08);overflow:hidden;\">"
                // Header: logo in a white disc over the brand gradient, then the title
                + "<tr><td align=\"center\" bgcolor=\"" + PRIMARY + "\" style=\"background:" + PRIMARY + ";background-image:" + GRADIENT + ";padding:36px 40px 30px;text-align:center;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 auto 18px;\"><tr>"
                + "<td align=\"center\" valign=\"middle\" width=\"80\" height=\"80\" bgcolor=\"#ffffff\" "
                + "style=\"width:80px;height:80px;background-color:rgba(255,255,255,0.85);border-radius:50%;text-align:center;vertical-align:middle;\">"
                + "<img src=\"" + esc(logo) + "\" alt=\"" + brand + "\" width=\"64\" height=\"49\" style=\"display:block;border:0;margin:0 auto;max-width:64px;height:auto;\" />"
                + "</td></tr></table>"
                + "<h1 style=\"margin:0;font-size:24px;line-height:1.3;font-weight:600;color:#ffffff;letter-spacing:-0.3px;\">" + title + "</h1>"
                + "</td></tr>"
                // Body
                + "<tr><td style=\"padding:36px 40px;text-align:" + align + ";font-size:15px;line-height:1.6;color:#4b5563;\">"
                + content
                + "</td></tr>"
                // Footer
                + "<tr><td align=\"center\" bgcolor=\"#f8fafc\" style=\"padding:26px 40px;background-color:#f8fafc;border-top:1px solid #e5e7eb;text-align:center;\">"
                + (isBlank(footerNote) ? "" : "<p style=\"margin:0 0 12px;font-size:12px;line-height:1.5;color:#9ca3af;\">" + footerNote + "</p>")
                + "<a href=\"" + BRAND_URL + "\" style=\"text-decoration:none;\">"
                + "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 auto;\"><tr>"
                + "<td valign=\"middle\" style=\"padding:0 6px;\"><img src=\"" + esc(logo) + "\" alt=\"\" width=\"37\" height=\"28\" style=\"display:block;border:0;max-width:37px;height:auto;\" /></td>"
                + "<td valign=\"middle\"><span style=\"font-size:15px;font-weight:700;color:" + PRIMARY + ";letter-spacing:0.08em;text-transform:uppercase;\">" + brand + "</span></td>"
                + "</tr></table></a>"
                + "</td></tr>"
                + "</table>"
                + "</td></tr></table>"
                + "</body></html>";
    }

    /** Emphasised opening line (greeting / who did what). */
    public static String lead(String html) {
        return "<p style=\"margin:0 0 16px;font-size:16px;font-weight:600;line-height:1.6;color:#1a1a2e;\">" + html + "</p>";
    }

    public static String paragraph(String html) {
        return "<p style=\"margin:0 0 16px;font-size:15px;line-height:1.6;color:#4b5563;\">" + html + "</p>";
    }

    /** Small grey print (expiry, fallback link, disclaimers). */
    public static String note(String html) {
        return "<p style=\"margin:12px 0 0;font-size:13px;line-height:1.6;color:#6b7280;\">" + html + "</p>";
    }

    /** Bulletproof call-to-action button (solid fallback colour for Outlook). */
    public static String button(String href, String label) {
        return "<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\" style=\"margin:28px auto;\"><tr>"
                + "<td align=\"center\" bgcolor=\"" + PRIMARY + "\" style=\"border-radius:8px;background:" + PRIMARY + ";background-image:" + GRADIENT + ";\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:14px 36px;font-size:15px;font-weight:600;color:#ffffff;"
                + "text-decoration:none;border-radius:8px;\">" + label + "</a>"
                + "</td></tr></table>";
    }

    /** Quoted user text (personal message, comment). */
    public static String quote(String html) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 20px;\"><tr>"
                + "<td bgcolor=\"#f8fafc\" style=\"background-color:#f8fafc;border-left:4px solid " + PRIMARY + ";border-radius:8px;padding:14px 18px;"
                + "font-size:15px;line-height:1.6;color:#374151;font-style:italic;\">" + html + "</td>"
                + "</tr></table>";
    }

    /** Highlighted box: {@code warning} = amber, otherwise neutral. */
    public static String callout(String html, boolean warning) {
        String bg = warning ? "#fffbeb" : "#f8fafc";
        String border = warning ? "#f59e0b" : "#e5e7eb";
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"margin:0 0 20px;\"><tr>"
                + "<td bgcolor=\"" + bg + "\" style=\"background-color:" + bg + ";border:1px solid " + border + ";border-radius:12px;padding:16px 18px;"
                + "font-size:14px;line-height:1.6;color:#4b5563;\">" + html + "</td>"
                + "</tr></table>";
    }

    /** A one-time code, large and spaced. */
    public static String code(String code) {
        return "<p style=\"margin:8px 0 20px;text-align:center;\"><span style=\"display:inline-block;padding:14px 26px;background-color:#f1f3ff;"
                + "border:1px dashed " + PRIMARY + ";border-radius:12px;font-size:30px;font-weight:700;letter-spacing:8px;color:#1a1a2e;"
                + "font-family:'SFMono-Regular',Consolas,'Liberation Mono',monospace;\">" + esc(code) + "</span></p>";
    }

    /** "If the button does not work" line with the raw link. */
    public static String linkFallback(String label, String href) {
        return note(label + "<br><a href=\"" + href + "\" style=\"color:" + PRIMARY + ";word-break:break-all;\">" + href + "</a>");
    }

    public static String esc(String s) {
        return s == null ? "" : HtmlUtils.htmlEscape(s);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
