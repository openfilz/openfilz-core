package org.openfilz.dms.service.language;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TextLanguageDetectorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "fr|Le principe est simple : tu recrutes cinq entreprises qui ont un appel d'offres en cours. Yann produit la réponse à la main et tu recueilles leur avis sur le prix, puis nous décidons ensemble de la suite du projet avec les résultats.",
            "en|The principle is simple: you recruit five companies that have a tender in progress. Yann writes the answer by hand and you collect their opinion about the price, then we decide together what to do with the results.",
            "de|Das Prinzip ist einfach: Du gewinnst fünf Unternehmen, die gerade an einer Ausschreibung arbeiten. Yann schreibt die Antwort von Hand und du sammelst ihre Meinung zum Preis, dann entscheiden wir gemeinsam, wie es mit dem Projekt weitergeht.",
            "es|El principio es sencillo: buscas cinco empresas que tienen una licitación en curso. Yann redacta la respuesta a mano y tú recoges su opinión sobre el precio, luego decidimos juntos qué hacer con los resultados del proyecto.",
            "it|Il principio è semplice: trovi cinque aziende che hanno una gara in corso. Yann scrive la risposta a mano e tu raccogli il loro parere sul prezzo, poi decidiamo insieme che cosa fare con i risultati del progetto.",
            "nl|Het principe is eenvoudig: je zoekt vijf bedrijven die een aanbesteding hebben lopen. Yann schrijft het antwoord met de hand en jij verzamelt hun mening over de prijs, dan beslissen we samen wat we met de resultaten van het project doen.",
            "pt|O princípio é simples: você procura cinco empresas que têm uma licitação em curso. O Yann escreve a resposta à mão e você recolhe a opinião delas sobre o preço, depois decidimos juntos o que fazer com os resultados do projeto.",
            "ar|المبدأ بسيط: تختار خمس شركات لديها مناقصة جارية. يكتب يان الإجابة يدويا وتجمع أنت رأيهم في السعر، ثم نقرر معا ما سنفعله بنتائج المشروع في الأسابيع القادمة."
    })
    void detectsTheLanguage(String language, String text) {
        assertThat(TextLanguageDetector.detect(text))
                .hasValueSatisfying(d -> {
                    assertThat(d.language()).isEqualTo(language);
                    assertThat(d.confidence()).isGreaterThanOrEqualTo(TextLanguageDetector.MIN_CONFIDENCE);
                });
    }

    @Test
    void refusesTooLittleText() {
        assertThat(TextLanguageDetector.detect("Lauréo — Test assistant")).isEmpty();
        assertThat(TextLanguageDetector.detect("")).isEmpty();
        assertThat(TextLanguageDetector.detect(null)).isEmpty();
    }

    @Test
    void refusesTextWithoutFunctionWords() {
        assertThat(TextLanguageDetector.detect("Lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod tempor "
                + "incididunt ut labore dolore magna aliqua enim minim veniam quis nostrud exercitation ullamco laboris")).isEmpty();
    }
}
