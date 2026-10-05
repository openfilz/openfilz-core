package org.openfilz.dms.service.insight;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The kind a file name says outright: {@code CNI recto.jpg} is an identity document,
 * {@code Facture 2026-03.pdf} an invoice, {@code budget.xlsx} a spreadsheet. A closed, multilingual
 * lexicon matched on whole words of the name (accents, case, separators and camelCase ignored), so
 * it never guesses: a name naming two different kinds, or none, gives no hint.
 * <p>
 * It exists because the embedding classifiers read a file name badly when it is all they have —
 * a photo or a scan without OCR has no text, and the nearest description to {@code "File name:
 * CNI DJIBI RECTO.JPG"} is close to random. Words win over the extension ({@code Factures.xlsx}
 * is an invoice); the extension names only spreadsheets and slide decks.
 */
public final class FileNameKindHints {

    /** kind → words or phrases (normalised: lower case, no accents, single spaces). */
    private static final Map<String, List<String>> WORDS = Map.ofEntries(
            Map.entry("id-document", List.of("cni", "passeport", "passport", "reisepass", "pasaporte", "passaporto", "paspoort",
                    "passaporte", "ausweis", "personalausweis", "fuhrerschein", "dni", "carte identite", "carte d identite",
                    "carte nationale d identite", "piece d identite", "pieces d identite", "titre de sejour", "permis de conduire",
                    "permis conduire", "driving licence", "driving license", "driver license", "drivers license",
                    "driver s license", "identity card", "id card", "photo id", "national id", "residence permit",
                    "carta d identita", "carta identita", "documento de identidad", "identiteitskaart", "rijbewijs")),
            Map.entry("invoice", List.of("facture", "factures", "invoice", "invoices", "rechnung", "rechnungen", "factura",
                    "facturas", "fattura", "fatture", "factuur", "facturen", "fatura", "faturas")),
            Map.entry("quote", List.of("devis", "quotation", "quotations", "angebot", "kostenvoranschlag", "presupuesto",
                    "preventivo", "offerte", "orcamento")),
            Map.entry("contract", List.of("contrat", "contrats", "contract", "contracts", "vertrag", "vertrage", "contrato",
                    "contratos", "contratto", "contratti", "contracten", "agreement")),
            Map.entry("cv", List.of("cv", "curriculum vitae", "curriculum", "lebenslauf")),
            Map.entry("receipt", List.of("receipt", "receipts", "ticket de caisse", "quittung", "kassenbon", "ricevuta",
                    "recibo", "bonnetje", "kassabon")),
            Map.entry("minutes", List.of("proces verbal", "meeting minutes", "compte rendu de reunion", "protokoll", "notulen",
                    "verbale")),
            Map.entry("report", List.of("rapport", "rapports", "report", "reports", "bericht", "informe", "relazione",
                    "relatorio")),
            Map.entry("manual", List.of("manuel", "manual", "mode d emploi", "notice d utilisation", "user guide",
                    "handbuch", "bedienungsanleitung", "handleiding", "manuale")),
            Map.entry("form", List.of("formulaire", "cerfa", "formular", "formulario", "formulier")),
            Map.entry("specification", List.of("cahier des charges", "specification", "specifications", "spec fonctionnelle",
                    "specifications fonctionnelles", "lastenheft", "pflichtenheft")));

    private static final Map<String, String> EXTENSIONS = Map.ofEntries(
            Map.entry("xlsx", "spreadsheet"), Map.entry("xls", "spreadsheet"), Map.entry("xlsm", "spreadsheet"),
            Map.entry("ods", "spreadsheet"), Map.entry("csv", "spreadsheet"), Map.entry("numbers", "spreadsheet"),
            Map.entry("pptx", "presentation"), Map.entry("ppt", "presentation"), Map.entry("odp", "presentation"),
            Map.entry("key", "presentation"));

    private FileNameKindHints() {
    }

    /**
     * The kind the name names, when exactly one kind of {@code allowed} is named.
     *
     * @param fileName the file name, extension included
     * @param allowed  the kinds the deployment classifies into (a hint outside them is dropped)
     */
    public static Optional<String> kindOf(String fileName, Set<String> allowed) {
        if (fileName == null || fileName.isBlank()) {
            return Optional.empty();
        }
        String base = fileName.trim();
        String extension = "";
        int dot = base.lastIndexOf('.');
        if (dot > 0 && dot < base.length() - 1) {
            extension = base.substring(dot + 1).toLowerCase(Locale.ROOT);
            base = base.substring(0, dot);
        }
        // "130 CV" is horsepower, not a résumé
        String words = (" " + normalise(base) + " ").replaceAll(" (\\d+) cv(?= )", " $1");
        Set<String> named = new LinkedHashSet<>();
        WORDS.forEach((kind, phrases) -> {
            for (String phrase : phrases) {
                if (words.contains(" " + phrase + " ")) {
                    named.add(kind);
                    break;
                }
            }
        });
        named.retainAll(allowed == null ? Set.of() : allowed);
        if (named.size() == 1) {
            return Optional.of(named.iterator().next());
        }
        if (!named.isEmpty()) {
            return Optional.empty();
        }
        String byExtension = EXTENSIONS.get(extension);
        return byExtension != null && allowed != null && allowed.contains(byExtension) ? Optional.of(byExtension) : Optional.empty();
    }

    /** Lower case, no accents, camelCase and digits split off, every separator a single space. */
    static String normalise(String name) {
        String split = name.replaceAll("(\\p{Ll})(\\p{Lu})", "$1 $2")
                .replaceAll("(\\p{L})(\\p{N})", "$1 $2")
                .replaceAll("(\\p{N})(\\p{L})", "$1 $2");
        String stripped = Normalizer.normalize(split, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        List<String> tokens = new ArrayList<>();
        for (String token : stripped.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (!token.isEmpty()) tokens.add(token);
        }
        return String.join(" ", tokens);
    }
}
