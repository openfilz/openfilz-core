package org.openfilz.dms.service.language;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.openfilz.dms.service.language.TestDocx.FRENCH;
import static org.openfilz.dms.service.language.TestDocx.styles;

class DocxLanguageTest {

    @TempDir
    Path dir;

    @Test
    void documentWithoutLanguage() throws IOException {
        DocxLanguage.Inspection inspection = inspect(TestDocx.build(styles("<w:style w:styleId=\"Normal\"/>"), FRENCH, "Deuxième paragraphe"));

        assertThat(inspection.defaultLanguage()).isNull();
        assertThat(inspection.declaredLanguage()).isNull();
        assertThat(inspection.languages()).isEmpty();
        assertThat(inspection.rewritable()).isTrue();
        assertThat(inspection.text()).contains("appel d'offres").contains("Deuxième paragraphe");
    }

    @Test
    void readsTheDefaultAndTheRunLanguages() throws IOException {
        String styles = styles("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\"/>"
                + "<w:lang w:val=\"en-US\" w:eastAsia=\"en-US\" w:bidi=\"ar-SA\"/></w:rPr></w:rPrDefault></w:docDefaults>");
        Map<String, String> parts = TestDocx.parts(TestDocx.build(styles, FRENCH));
        parts.put("word/document.xml", parts.get("word/document.xml")
                .replace("<w:r>", "<w:r><w:rPr><w:lang w:val=\"en-GB\"/></w:rPr>"));

        DocxLanguage.Inspection inspection = inspect(TestDocx.zip(parts));

        assertThat(inspection.defaultLanguage()).isEqualTo("en-US");
        assertThat(inspection.declaredLanguage()).isEqualTo("en-US");
        assertThat(inspection.languages()).containsEntry("en-US", 1).containsEntry("en-GB", 1);
    }

    @Test
    void runLanguageStandsInForAMissingDefault() throws IOException {
        Map<String, String> parts = TestDocx.parts(TestDocx.build(styles(""), FRENCH, FRENCH));
        parts.put("word/document.xml", parts.get("word/document.xml")
                .replace("<w:r>", "<w:r><w:rPr><w:lang w:val=\"de-DE\"/></w:rPr>"));

        assertThat(inspect(TestDocx.zip(parts)).declaredLanguage()).isEqualTo("de-DE");
    }

    @Test
    void decodesEntitiesInTheText() {
        assertThat(DocxLanguage.text("<w:p><w:r><w:t>Tom &amp; Jerry &lt;3 &#233;t&#xE9;</w:t></w:r></w:p>"))
                .isEqualTo("Tom & Jerry <3 été ");
    }

    @Test
    void writesTheDefaultIntoAStylePartWithoutDocDefaults() throws IOException {
        String styles = rewrite(TestDocx.build(styles("<w:style w:styleId=\"Normal\"/>"), FRENCH), "fr-FR", null)
                .get("word/styles.xml");

        assertThat(styles).contains("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault></w:docDefaults>"
                + "<w:style w:styleId=\"Normal\"/>");
        assertThat(DocxLanguage.defaultLanguage(styles)).isEqualTo("fr-FR");
    }

    @Test
    void addsTheLanguageInSchemaOrderToExistingRunProperties() {
        String styles = styles("<w:docDefaults><w:rPrDefault><w:rPr><w:rFonts w:ascii=\"Calibri\"/><w:sz w:val=\"22\"/>"
                + "<w:eastAsianLayout w:id=\"1\"/></w:rPr></w:rPrDefault><w:pPrDefault/></w:docDefaults>");

        String updated = DocxLanguage.withDefaultLanguage(styles, "fr-FR");

        assertThat(updated).contains("<w:sz w:val=\"22\"/><w:lang w:val=\"fr-FR\"/><w:eastAsianLayout w:id=\"1\"/>")
                .contains("<w:pPrDefault/>");
    }

    @Test
    void fillsEmptyDefaults() {
        assertThat(DocxLanguage.withDefaultLanguage(styles("<w:docDefaults/>"), "fr-FR"))
                .contains("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault></w:docDefaults>");
        assertThat(DocxLanguage.withDefaultLanguage(styles("<w:docDefaults><w:rPrDefault/></w:docDefaults>"), "fr-FR"))
                .contains("<w:rPrDefault><w:rPr><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault>");
        assertThat(DocxLanguage.withDefaultLanguage(styles("<w:docDefaults><w:rPrDefault><w:rPr/></w:rPrDefault></w:docDefaults>"), "fr-FR"))
                .contains("<w:rPrDefault><w:rPr><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault>");
    }

    @Test
    void movesThePreviousLanguageAndKeepsTheOthers() throws IOException {
        String styles = styles("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"en-US\" w:eastAsia=\"zh-CN\"/></w:rPr>"
                + "</w:rPrDefault></w:docDefaults>");
        Map<String, String> parts = TestDocx.parts(TestDocx.build(styles, FRENCH, "Quote", "Zitat"));
        String document = parts.get("word/document.xml");
        document = document.replaceFirst("<w:r>", "<w:r><w:rPr><w:lang w:val=\"en-GB\"/></w:rPr>");
        document = document.replaceFirst("<w:r>(?=<w:t[^>]*>Zitat)", "<w:r><w:rPr><w:lang w:val=\"de-DE\"/></w:rPr>");
        parts.put("word/document.xml", document);
        parts.put("word/header1.xml", "<w:hdr " + TestDocx.W + "><w:p><w:r><w:rPr><w:lang w:val=\"en-US\"/></w:rPr><w:t>H</w:t></w:r></w:p></w:hdr>");

        Map<String, String> result = rewrite(TestDocx.zip(parts), "fr-FR", "en-US");

        assertThat(result.get("word/styles.xml")).contains("<w:lang w:val=\"fr-FR\" w:eastAsia=\"zh-CN\"/>");
        assertThat(result.get("word/document.xml")).contains("w:val=\"fr-FR\"").contains("w:val=\"de-DE\"").doesNotContain("en-GB");
        assertThat(result.get("word/header1.xml")).contains("w:val=\"fr-FR\"");
    }

    @Test
    void keepsTheOtherPartsAndTheirOrder() throws IOException {
        byte[] docx = TestDocx.build(styles(""), FRENCH);
        Map<String, String> before = TestDocx.parts(docx);

        Map<String, String> after = rewrite(docx, "fr-FR", null);

        assertThat(List.copyOf(after.keySet())).isEqualTo(List.copyOf(before.keySet()));
        assertThat(after.get("[Content_Types].xml")).isEqualTo(before.get("[Content_Types].xml"));
        assertThat(after.get("word/document.xml")).isEqualTo(before.get("word/document.xml"));
    }

    @Test
    void arabicAlsoSetsTheComplexScriptLanguage() {
        assertThat(DocxLanguage.withDefaultLanguage(styles(""), "ar-SA"))
                .contains("<w:lang w:val=\"ar-SA\" w:bidi=\"ar-SA\"/>");
    }

    @Test
    void refusesADocumentWithoutStylePart() throws IOException {
        byte[] docx = TestDocx.build(null, FRENCH);
        assertThat(inspect(docx).rewritable()).isFalse();

        Path source = Files.write(dir.resolve("in.docx"), docx);
        assertThatThrownBy(() -> DocxLanguage.rewrite(source, dir.resolve("out.docx"), "fr-FR", null))
                .isInstanceOf(IOException.class);
    }

    @Test
    void refusesWhatIsNotAWordDocument() throws IOException {
        Path file = Files.write(dir.resolve("x.docx"), TestDocx.zip(Map.of("a.txt", "a")));
        assertThatThrownBy(() -> DocxLanguage.inspect(file)).isInstanceOf(IOException.class);
        Path text = Files.writeString(dir.resolve("y.docx"), "not a zip");
        assertThatThrownBy(() -> DocxLanguage.inspect(text)).isInstanceOf(IOException.class);
    }

    @Test
    void languageTags() {
        assertThat(DocumentLanguages.tagFor("fr")).isEqualTo("fr-FR");
        assertThat(DocumentLanguages.tagFor("pt")).isEqualTo("pt-PT");
        assertThat(DocumentLanguages.uiLanguage("fr")).isEqualTo("fr");
        assertThat(DocumentLanguages.uiLanguage("pt-br")).isEqualTo("pt-BR");
        assertThat(DocumentLanguages.uiLanguage(null)).isEqualTo("en");
        assertThat(DocumentLanguages.uiLanguage("fr\";alert(1)")).isEqualTo("en");
        assertThat(DocumentLanguages.isValidTag("fr-FR")).isTrue();
        assertThat(DocumentLanguages.isValidTag("fr")).isFalse();
        assertThat(DocumentLanguages.isValidTag("fr-FR\" w:x=\"")).isFalse();
    }

    private DocxLanguage.Inspection inspect(byte[] docx) throws IOException {
        return DocxLanguage.inspect(Files.write(Files.createTempFile(dir, "doc", ".docx"), docx));
    }

    private Map<String, String> rewrite(byte[] docx, String language, String previous) throws IOException {
        Path source = Files.write(Files.createTempFile(dir, "in", ".docx"), docx);
        Path target = dir.resolve("out-" + System.nanoTime() + ".docx");
        DocxLanguage.rewrite(source, target, language, previous);
        return TestDocx.parts(Files.readAllBytes(target));
    }
}
