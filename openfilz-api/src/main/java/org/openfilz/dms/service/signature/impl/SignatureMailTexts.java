package org.openfilz.dms.service.signature.impl;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * Localised strings for the e-Sign emails, loaded from
 * {@code signature-mail/messages_<lang>.properties} (en, fr, de, es, it, nl, pt, ar — English
 * fallback). Kept as a plain {@link ResourceBundle} so the mailer has no Spring dependency and
 * works identically in native images (the bundles are registered as resources).
 */
public final class SignatureMailTexts {

    public static final Set<String> SUPPORTED = Set.of("en", "fr", "de", "es", "it", "nl", "pt", "ar");
    private static final String BASE = "signature-mail.messages";

    private SignatureMailTexts() {}

    public static Locale localeOf(String code) {
        if (code == null || code.isBlank()) return Locale.ENGLISH;
        String lang = code.trim().toLowerCase().split("[-_]")[0];
        return SUPPORTED.contains(lang) ? Locale.forLanguageTag(lang) : Locale.ENGLISH;
    }

    public static String text(Locale locale, String key, Object... args) {
        String pattern;
        try {
            pattern = bundle(locale).getString(key);
        } catch (MissingResourceException e) {
            try {
                pattern = bundle(Locale.ENGLISH).getString(key);
            } catch (MissingResourceException e2) {
                pattern = key;
            }
        }
        // MessageFormat treats single quotes as escapes — double them so "l'enveloppe" renders.
        return new MessageFormat(pattern.replace("'", "''"), locale).format(args);
    }

    private static ResourceBundle bundle(Locale locale) {
        return ResourceBundle.getBundle(BASE, locale, ResourceBundle.Control.getNoFallbackControl(
                ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public static boolean isRtl(Locale locale) {
        return "ar".equals(locale.getLanguage());
    }
}
