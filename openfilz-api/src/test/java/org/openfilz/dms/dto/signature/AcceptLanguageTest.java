package org.openfilz.dms.dto.signature;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptLanguageTest {

    @Test
    void picks_the_best_ranked_language_of_the_header() {
        assertThat(AcceptLanguage.firstLanguage("fr-FR,fr;q=0.9,en;q=0.8")).isEqualToIgnoringCase("fr-FR");
        assertThat(AcceptLanguage.firstLanguage("en;q=0.5, de;q=0.9")).isEqualTo("de");
        assertThat(AcceptLanguage.firstLanguage("  pt-BR ")).isEqualToIgnoringCase("pt-BR");
    }

    @Test
    void answers_nothing_for_blank_wildcard_or_malformed_headers() {
        assertThat(AcceptLanguage.firstLanguage(null)).isNull();
        assertThat(AcceptLanguage.firstLanguage("   ")).isNull();
        assertThat(AcceptLanguage.firstLanguage("*")).isNull();
        assertThat(AcceptLanguage.firstLanguage("fr;q=0")).isNull();
        assertThat(AcceptLanguage.firstLanguage("not a header ;;; q=")).isNull();
    }

    @Test
    void long_tags_are_cut_to_the_locale_field_size() {
        assertThat(AcceptLanguage.firstLanguage("zh-Hant-TW-x-private")).hasSize(8);
    }

    @Test
    void the_request_takes_the_header_only_when_it_names_no_locale() {
        CreateSignatureEnvelopeRequest req = new CreateSignatureEnvelopeRequest(UUID.randomUUID(), "T", null,
                List.of(new SignatureRecipientInput(null, "A", "a@example.com", 0, null, null, null, null, List.of(), null)),
                null, null, null, null, null, null);

        assertThat(req.withLocaleFallback("fr-FR,fr;q=0.9").locale()).isEqualToIgnoringCase("fr-FR");
        assertThat(req.withLocaleFallback(null)).isSameAs(req);

        CreateSignatureEnvelopeRequest explicit = req.withLocaleFallback("de");
        assertThat(explicit.withLocaleFallback("fr")).isSameAs(explicit);
        assertThat(explicit.locale()).isEqualTo("de");
    }
}
