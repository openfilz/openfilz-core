package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailLayoutTest {

    @Test
    void page_withoutLogo_usesHostedOpenFilzLogoAndBrand() {
        String html = EmailLayout.page("fr", false, "Titre", EmailLayout.paragraph("Corps"), "Note", null, "");

        assertThat(html)
                .startsWith("<!DOCTYPE html>")
                .contains("lang=\"fr\"", "dir=\"ltr\"")
                .contains("<h1", "Titre", "Corps", "Note")
                .contains(EmailLayout.DEFAULT_LOGO_URL)
                .contains(">OpenFilz</span>");
    }

    @Test
    void page_whiteLabel_rtl_usesConfiguredLogoAndEscapesBrand() {
        String html = EmailLayout.page("ar", true, "T", "", "", "Acme <Docs>", "https://cdn.acme.test/logo.png?a=1&b=2");

        assertThat(html)
                .contains("dir=\"rtl\"", "text-align:right")
                .contains("https://cdn.acme.test/logo.png?a=1&amp;b=2")
                .doesNotContain(EmailLayout.DEFAULT_LOGO_URL)
                .contains("Acme &lt;Docs&gt;")
                .doesNotContain("<p style=\"margin:0 0 12px;font-size:12px");
    }

    @Test
    void code_isEscaped() {
        assertThat(EmailLayout.code("<1>")).contains("&lt;1&gt;").doesNotContain("<1>");
    }
}
