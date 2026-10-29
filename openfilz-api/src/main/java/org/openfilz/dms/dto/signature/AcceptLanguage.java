package org.openfilz.dms.dto.signature;

import java.util.List;
import java.util.Locale;

/**
 * The language a caller's {@code Accept-Language} header asks for first — the e-mail language of a
 * signature request whose body names none. Browsers always send the header (their UI language),
 * API clients may send nothing; both cases end up in the same place.
 */
public final class AcceptLanguage {

    private AcceptLanguage() {
    }

    /**
     * @return the best-ranked language tag of the header, lower-cased as {@link Locale.LanguageRange}
     * reports it ({@code "fr-fr"} for {@code "fr-FR,fr;q=0.9,en;q=0.8"} — the service keeps only the
     * language part anyway), at most 8 characters so it fits the request's {@code locale} field, or
     * {@code null} for a blank, malformed or wildcard-only header.
     */
    public static String firstLanguage(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) return null;
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(acceptLanguage.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        for (Locale.LanguageRange range : ranges) { // already sorted by weight, highest first
            String tag = range.getRange();
            if (tag == null || tag.isBlank() || tag.startsWith("*") || range.getWeight() <= 0) continue;
            return tag.length() > 8 ? tag.substring(0, 8) : tag;
        }
        return null;
    }
}
