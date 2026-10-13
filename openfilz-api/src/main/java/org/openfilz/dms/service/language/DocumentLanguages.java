package org.openfilz.dms.service.language;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Language codes as the OnlyOffice editor and Word documents expect them. */
public final class DocumentLanguages {

    /** The region a detected language is written with when nothing says otherwise. */
    private static final Map<String, String> DEFAULT_TAGS = Map.of(
            "en", "en-US",
            "fr", "fr-FR",
            "de", "de-DE",
            "es", "es-ES",
            "it", "it-IT",
            "nl", "nl-NL",
            "pt", "pt-PT",
            "ar", "ar-SA");

    private static final Pattern UI_LANG = Pattern.compile("[a-z]{2,3}(-[A-Za-z]{2,4})?");
    private static final Pattern TAG = Pattern.compile("[a-z]{2,3}-[A-Z]{2}");

    public static final String DEFAULT_UI_LANG = "en";

    private DocumentLanguages() {
    }

    /** {@code fr} → {@code fr-FR}; a language without a known region gets none. */
    public static String tagFor(String language) {
        String base = DocxLanguage.base(language);
        return DEFAULT_TAGS.getOrDefault(base, base);
    }

    /** The editor interface language: the caller's when it looks like one, else {@link #DEFAULT_UI_LANG}. */
    public static String uiLanguage(String requested) {
        if (requested == null) {
            return DEFAULT_UI_LANG;
        }
        String lang = requested.trim();
        if (!UI_LANG.matcher(lang).matches()) {
            return DEFAULT_UI_LANG;
        }
        int dash = lang.indexOf('-');
        if (dash < 0) {
            return lang;
        }
        String region = lang.substring(dash + 1);
        // a 2-letter region is upper-cased (pt-br → pt-BR); a script subtag keeps its case (zh-Hans)
        return lang.substring(0, dash) + "-" + (region.length() == 2 ? region.toUpperCase(Locale.ROOT) : region);
    }

    /** A language a document may be set to: {@code ll-RR} (BCP 47 language + region). */
    public static boolean isValidTag(String tag) {
        return tag != null && TAG.matcher(tag).matches();
    }
}
