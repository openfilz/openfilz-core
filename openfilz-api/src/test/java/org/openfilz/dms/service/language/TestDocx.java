package org.openfilz.dms.service.language;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Builds minimal Word documents for the language tests, and reads parts back. */
public final class TestDocx {

    public static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    public static final String FRENCH = "Le principe est simple : tu recrutes cinq entreprises qui ont un appel d'offres en cours. "
            + "Yann produit la réponse à la main, avec l'analyse du dossier et la liste des pièces. "
            + "Tu la leur remets et tu recueilles leur avis sur le prix. Les entreprises sont des PME de dix à cent salariés "
            + "qui répondent au moins à un appel d'offres par mois dans le nettoyage, le bâtiment ou la formation.";

    public static final String ENGLISH = "The principle is simple: you recruit five companies that have a tender in progress. "
            + "Yann writes the answer by hand, with the analysis of the file and the list of documents. "
            + "You hand it over to them and they give their opinion about the price. These are small companies "
            + "which answer at least one tender per month in cleaning, building or training, and they should be ready.";

    private TestDocx() {
    }

    /** A document with one paragraph per text; {@code styles} = null leaves the style part out. */
    public static byte[] build(String styles, String... paragraphs) {
        StringBuilder body = new StringBuilder();
        for (String p : paragraphs) {
            body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(p.replace("&", "&amp;").replace("<", "&lt;"))
                    .append("</w:t></w:r></w:p>");
        }
        String document = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document " + W + "><w:body>"
                + body + "</w:body></w:document>";
        Map<String, String> parts = new LinkedHashMap<>();
        parts.put("[Content_Types].xml", "<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>");
        parts.put("word/document.xml", document);
        if (styles != null) {
            parts.put("word/styles.xml", styles);
        }
        return zip(parts);
    }

    /** A {@code styles.xml} whose root holds {@code inner}. */
    public static String styles(String inner) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:styles " + W + ">" + inner + "</w:styles>";
    }

    public static byte[] zip(Map<String, String> parts) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(out)) {
                for (Map.Entry<String, String> part : parts.entrySet()) {
                    zip.putNextEntry(new ZipEntry(part.getKey()));
                    zip.write(part.getValue().getBytes(StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The parts of a document, in their order in the archive. */
    public static Map<String, String> parts(byte[] docx) {
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(docx))) {
            Map<String, String> parts = new LinkedHashMap<>();
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                parts.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
            return parts;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
