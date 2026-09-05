package org.openfilz.dms.service.filing;

import java.text.Normalizer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * What a folder for a kind of document is called, in the languages OpenFilz ships — the
 * folder-name table that lets smart filing and the by-kind reorganisation create
 * {@code Invoices} / {@code Factures} / {@code Rechnungen} without asking a model. It also reads
 * the other way: which kind an existing folder name denotes, and which language a library's
 * folder names are in. Deployments override or extend it with
 * {@code openfilz.ai.auto-file.folder-names.<lang>.<category>}.
 */
public final class CategoryFolderNames {

    public static final String DEFAULT_LANGUAGE = "en";

    /** language → category → folder name. */
    private static final Map<String, Map<String, String>> BUILT_IN = Map.of(
            "en", Map.ofEntries(
                    Map.entry("invoice", "Invoices"), Map.entry("quote", "Quotes"), Map.entry("contract", "Contracts"),
                    Map.entry("report", "Reports"), Map.entry("letter", "Letters"), Map.entry("cv", "CVs"),
                    Map.entry("presentation", "Presentations"), Map.entry("spreadsheet", "Spreadsheets"),
                    Map.entry("form", "Forms"), Map.entry("id-document", "ID documents"), Map.entry("receipt", "Receipts"),
                    Map.entry("minutes", "Minutes"), Map.entry("specification", "Specifications"), Map.entry("manual", "Manuals")),
            "fr", Map.ofEntries(
                    Map.entry("invoice", "Factures"), Map.entry("quote", "Devis"), Map.entry("contract", "Contrats"),
                    Map.entry("report", "Rapports"), Map.entry("letter", "Courriers"), Map.entry("cv", "CV"),
                    Map.entry("presentation", "Présentations"), Map.entry("spreadsheet", "Tableurs"),
                    Map.entry("form", "Formulaires"), Map.entry("id-document", "Pièces d'identité"), Map.entry("receipt", "Reçus"),
                    Map.entry("minutes", "Comptes rendus"), Map.entry("specification", "Spécifications"), Map.entry("manual", "Manuels")),
            "de", Map.ofEntries(
                    Map.entry("invoice", "Rechnungen"), Map.entry("quote", "Angebote"), Map.entry("contract", "Verträge"),
                    Map.entry("report", "Berichte"), Map.entry("letter", "Briefe"), Map.entry("cv", "Lebensläufe"),
                    Map.entry("presentation", "Präsentationen"), Map.entry("spreadsheet", "Tabellen"),
                    Map.entry("form", "Formulare"), Map.entry("id-document", "Ausweisdokumente"), Map.entry("receipt", "Belege"),
                    Map.entry("minutes", "Protokolle"), Map.entry("specification", "Spezifikationen"), Map.entry("manual", "Handbücher")),
            "es", Map.ofEntries(
                    Map.entry("invoice", "Facturas"), Map.entry("quote", "Presupuestos"), Map.entry("contract", "Contratos"),
                    Map.entry("report", "Informes"), Map.entry("letter", "Cartas"), Map.entry("cv", "Currículums"),
                    Map.entry("presentation", "Presentaciones"), Map.entry("spreadsheet", "Hojas de cálculo"),
                    Map.entry("form", "Formularios"), Map.entry("id-document", "Documentos de identidad"), Map.entry("receipt", "Recibos"),
                    Map.entry("minutes", "Actas"), Map.entry("specification", "Especificaciones"), Map.entry("manual", "Manuales")),
            "it", Map.ofEntries(
                    Map.entry("invoice", "Fatture"), Map.entry("quote", "Preventivi"), Map.entry("contract", "Contratti"),
                    Map.entry("report", "Relazioni"), Map.entry("letter", "Lettere"), Map.entry("cv", "Curriculum"),
                    Map.entry("presentation", "Presentazioni"), Map.entry("spreadsheet", "Fogli di calcolo"),
                    Map.entry("form", "Moduli"), Map.entry("id-document", "Documenti d'identità"), Map.entry("receipt", "Ricevute"),
                    Map.entry("minutes", "Verbali"), Map.entry("specification", "Specifiche"), Map.entry("manual", "Manuali")),
            "nl", Map.ofEntries(
                    Map.entry("invoice", "Facturen"), Map.entry("quote", "Offertes"), Map.entry("contract", "Contracten"),
                    Map.entry("report", "Rapporten"), Map.entry("letter", "Brieven"), Map.entry("cv", "CV's"),
                    Map.entry("presentation", "Presentaties"), Map.entry("spreadsheet", "Spreadsheets"),
                    Map.entry("form", "Formulieren"), Map.entry("id-document", "Identiteitsdocumenten"), Map.entry("receipt", "Bonnetjes"),
                    Map.entry("minutes", "Notulen"), Map.entry("specification", "Specificaties"), Map.entry("manual", "Handleidingen")),
            "pt", Map.ofEntries(
                    Map.entry("invoice", "Faturas"), Map.entry("quote", "Orçamentos"), Map.entry("contract", "Contratos"),
                    Map.entry("report", "Relatórios"), Map.entry("letter", "Cartas"), Map.entry("cv", "Currículos"),
                    Map.entry("presentation", "Apresentações"), Map.entry("spreadsheet", "Folhas de cálculo"),
                    Map.entry("form", "Formulários"), Map.entry("id-document", "Documentos de identidade"), Map.entry("receipt", "Recibos"),
                    Map.entry("minutes", "Atas"), Map.entry("specification", "Especificações"), Map.entry("manual", "Manuais")),
            "ar", Map.ofEntries(
                    Map.entry("invoice", "فواتير"), Map.entry("quote", "عروض أسعار"), Map.entry("contract", "عقود"),
                    Map.entry("report", "تقارير"), Map.entry("letter", "رسائل"), Map.entry("cv", "سير ذاتية"),
                    Map.entry("presentation", "عروض تقديمية"), Map.entry("spreadsheet", "جداول بيانات"),
                    Map.entry("form", "نماذج"), Map.entry("id-document", "وثائق هوية"), Map.entry("receipt", "إيصالات"),
                    Map.entry("minutes", "محاضر"), Map.entry("specification", "مواصفات"), Map.entry("manual", "أدلة")));

    /**
     * Other spellings a folder may carry for a kind, read but never written: singulars, common
     * variants. language → category → names.
     */
    private static final Map<String, Map<String, List<String>>> ALIASES = Map.of(
            "en", Map.of("invoice", List.of("Invoice", "Billing", "Bills"), "quote", List.of("Quote", "Quotations", "Estimates"),
                    "contract", List.of("Contract", "Agreements"), "report", List.of("Report"), "letter", List.of("Letter", "Correspondence"),
                    "cv", List.of("CV", "Resumes", "Résumés"), "minutes", List.of("Meeting minutes"), "receipt", List.of("Receipt"),
                    "manual", List.of("Manual", "Guides"), "presentation", List.of("Presentation", "Slides")),
            "fr", Map.of("invoice", List.of("Facture", "Facturation"), "contract", List.of("Contrat"), "report", List.of("Rapport"),
                    "letter", List.of("Courrier", "Lettres", "Correspondance"), "minutes", List.of("Compte rendu", "Comptes-rendus", "Procès-verbaux"),
                    "receipt", List.of("Reçu", "Tickets"), "manual", List.of("Manuel", "Notices"), "presentation", List.of("Présentation")),
            "de", Map.of("invoice", List.of("Rechnung"), "contract", List.of("Vertrag"), "report", List.of("Bericht"), "letter", List.of("Brief"),
                    "receipt", List.of("Beleg", "Quittungen"), "manual", List.of("Handbuch", "Anleitungen")));

    private final Map<String, Map<String, String>> names;      // language → category → name (with overrides)
    private final Map<String, Map<String, String>> byName;     // language → normalised name → category

    public CategoryFolderNames() {
        this(Map.of());
    }

    /** @param overrides {@code language → category → name}, on top of the built-in table */
    public CategoryFolderNames(Map<String, Map<String, String>> overrides) {
        Map<String, Map<String, String>> merged = new LinkedHashMap<>();
        BUILT_IN.forEach((language, table) -> merged.put(language, new LinkedHashMap<>(table)));
        if (overrides != null) {
            overrides.forEach((language, table) -> {
                if (language == null || table == null) return;
                Map<String, String> target = merged.computeIfAbsent(language.trim().toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>());
                table.forEach((category, name) -> {
                    if (category != null && name != null && !name.isBlank()) target.put(normaliseCategory(category), name.trim());
                });
            });
        }
        this.names = merged;
        Map<String, Map<String, String>> reverse = new LinkedHashMap<>();
        merged.forEach((language, table) -> {
            Map<String, String> index = reverse.computeIfAbsent(language, k -> new LinkedHashMap<>());
            table.forEach((category, name) -> index.put(key(name), category));
            // The built-in names still read when a deployment renames a kind: an older folder keeps its name
            BUILT_IN.getOrDefault(language, Map.of()).forEach((category, name) -> index.putIfAbsent(key(name), category));
            Map<String, List<String>> aliases = ALIASES.getOrDefault(language, Map.of());
            aliases.forEach((category, list) -> list.forEach(alias -> index.putIfAbsent(key(alias), category)));
        });
        this.byName = reverse;
    }

    /** The folder name for a kind in a language; falls back to English, then to nothing for {@code other} or an unknown kind. */
    public Optional<String> nameOf(String category, String language) {
        String kind = normaliseCategory(category);
        if (kind.isEmpty() || "other".equals(kind)) {
            return Optional.empty();
        }
        String lang = language == null || language.isBlank() ? DEFAULT_LANGUAGE
                : language.trim().toLowerCase(Locale.ROOT).split("[-_]")[0];
        String name = names.getOrDefault(lang, Map.of()).get(kind);
        if (name == null) name = names.getOrDefault(DEFAULT_LANGUAGE, Map.of()).get(kind);
        return Optional.ofNullable(name);
    }

    /** The kind an existing folder name denotes, in any language ("Factures" → invoice), if any. */
    public Optional<String> categoryOf(String folderName) {
        if (folderName == null || folderName.isBlank()) {
            return Optional.empty();
        }
        String key = key(folderName);
        for (Map<String, String> index : byName.values()) {
            String category = index.get(key);
            if (category != null) return Optional.of(category);
        }
        return Optional.empty();
    }

    /**
     * The language a library's folders are named in: the one whose table matches the most of
     * them. Empty when no name matches any table, or two languages tie — then the caller falls
     * back to the document's language or the deployment default.
     */
    public Optional<String> languageOf(Collection<String> folderNames) {
        if (folderNames == null || folderNames.isEmpty()) {
            return Optional.empty();
        }
        Map<String, Integer> hits = new LinkedHashMap<>();
        for (String folderName : folderNames) {
            if (folderName == null || folderName.isBlank()) continue;
            String key = key(folderName);
            byName.forEach((language, index) -> {
                if (index.containsKey(key)) hits.merge(language, 1, Integer::sum);
            });
        }
        String best = null;
        int bestCount = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : hits.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
                tie = false;
            } else if (entry.getValue() == bestCount) {
                tie = true;
            }
        }
        return best == null || tie ? Optional.empty() : Optional.of(best);
    }

    static String normaliseCategory(String category) {
        return category == null ? "" : category.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }

    /** Case-, accent- and punctuation-insensitive key of a folder name. */
    static String key(String name) {
        String stripped = Normalizer.normalize(name.trim(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return stripped.toLowerCase(Locale.ROOT).replaceAll("[\\s'’\\-_.]+", " ").trim();
    }
}
