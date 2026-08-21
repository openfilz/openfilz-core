package org.openfilz.dms.service.signature.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Locale;
import java.util.ResourceBundle;

import static org.assertj.core.api.Assertions.assertThat;

class SignatureMailTextsTest {

    @Test
    void localeOf_fallbacks() {
        assertThat(SignatureMailTexts.localeOf(null)).isEqualTo(Locale.ENGLISH);
        assertThat(SignatureMailTexts.localeOf("")).isEqualTo(Locale.ENGLISH);
        assertThat(SignatureMailTexts.localeOf("   ")).isEqualTo(Locale.ENGLISH);
        assertThat(SignatureMailTexts.localeOf("xx")).isEqualTo(Locale.ENGLISH);
        assertThat(SignatureMailTexts.localeOf("fr-FR")).isEqualTo(Locale.FRENCH);
        assertThat(SignatureMailTexts.localeOf("fr_CA")).isEqualTo(Locale.FRENCH);
        assertThat(SignatureMailTexts.localeOf(" DE ")).isEqualTo(Locale.GERMAN);
        assertThat(SignatureMailTexts.localeOf("pt-BR").getLanguage()).isEqualTo("pt");
        assertThat(SignatureMailTexts.localeOf("ar").getLanguage()).isEqualTo("ar");
    }

    @Test
    void text_formatsPlaceholders_english() {
        assertThat(SignatureMailTexts.text(Locale.ENGLISH, "request.subject", "alice@x.io", "Lease"))
                .isEqualTo("alice@x.io asks you to sign \"Lease\"");
        assertThat(SignatureMailTexts.text(Locale.ENGLISH, "request.body", "a", "b", "c"))
                .isEqualTo("a invites you to sign <b>b</b> (c).");
    }

    @Test
    void text_french_apostrophesAreNotSwallowedByMessageFormat() {
        String footer = SignatureMailTexts.text(Locale.FRENCH, "footer", "OpenFilz");
        assertThat(footer).isEqualTo("Envoyé par OpenFilz e-Sign. Si vous n'attendiez pas cet e-mail, vous pouvez l'ignorer.");
        String otp = SignatureMailTexts.text(Locale.FRENCH, "otp.title");
        assertThat(otp).isEqualTo("Votre code d'accès à usage unique");
        // placeholder directly after an apostrophe-bearing word still substitutes
        assertThat(SignatureMailTexts.text(Locale.FRENCH, "declined.body", "bob", "Bail"))
                .isEqualTo("bob a refusé de signer <b>Bail</b>. L'enveloppe a été annulée.");
    }

    @Test
    void text_missingKey_returnsKey() {
        assertThat(SignatureMailTexts.text(Locale.ENGLISH, "does.not.exist")).isEqualTo("does.not.exist");
        assertThat(SignatureMailTexts.text(Locale.FRENCH, "does.not.exist", "x")).isEqualTo("does.not.exist");
    }

    @Test
    void text_unknownLocale_fallsBackToEnglishBundle() {
        assertThat(SignatureMailTexts.text(Locale.forLanguageTag("xx"), "request.title")).isEqualTo("Document to sign");
        assertThat(SignatureMailTexts.text(Locale.JAPANESE, "request.button")).isEqualTo("Review and sign");
    }

    @Test
    void text_integerArgument_rendersAsPlainNumber() {
        assertThat(SignatureMailTexts.text(Locale.ENGLISH, "otp.valid", 10))
                .isEqualTo("The code is valid for 10 minutes and can only be used once.");
    }

    @Test
    void isRtl_onlyArabic() {
        assertThat(SignatureMailTexts.isRtl(Locale.forLanguageTag("ar"))).isTrue();
        assertThat(SignatureMailTexts.isRtl(Locale.ENGLISH)).isFalse();
        assertThat(SignatureMailTexts.isRtl(Locale.FRENCH)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"en", "fr", "de", "es", "it", "nl", "pt", "ar"})
    void everySupportedLocale_hasEveryEnglishKey_andKeepsPlaceholders(String lang) {
        ResourceBundle en = ResourceBundle.getBundle("signature-mail.messages", Locale.ENGLISH,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        Locale loc = Locale.forLanguageTag(lang);
        ResourceBundle b = ResourceBundle.getBundle("signature-mail.messages", loc,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
        assertThat(b.getLocale().getLanguage()).as("bundle resolved for " + lang).isEqualTo(lang);
        for (String key : en.keySet()) {
            assertThat(b.containsKey(key)).as("%s missing key %s", lang, key).isTrue();
            String enValue = en.getString(key);
            String value = b.getString(key);
            assertThat(value).isNotBlank();
            for (String ph : new String[]{"{0}", "{1}", "{2}"}) {
                assertThat(value.contains(ph)).as("%s/%s placeholder %s", lang, key, ph).isEqualTo(enValue.contains(ph));
            }
            assertThat(value.contains("<b>")).as("%s/%s <b> tag", lang, key).isEqualTo(enValue.contains("<b>"));
            // renders without MessageFormat errors
            SignatureMailTexts.text(loc, key, "a", "b", "c");
        }
        assertThat(b.keySet()).containsExactlyInAnyOrderElementsOf(en.keySet());
    }

    @Test
    void supportedSet_matchesBundles() {
        assertThat(SignatureMailTexts.SUPPORTED).containsExactlyInAnyOrder("en", "fr", "de", "es", "it", "nl", "pt", "ar");
    }
}
