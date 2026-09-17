package org.openfilz.dms.service.insight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What a kind of document is called, per language — the display name the web app shows for a
 * category key ({@code id-document} → {@code ID document} / {@code Pièce d’identité}). A label map
 * is {@code language → label}; English is the fallback every map is expected to carry, and a key
 * with no label at all shows as itself.
 * <p>
 * The built-in kinds ship labels in the languages the web app ships; a deployment adds or
 * overrides them ({@code openfilz.ai.insights.category-labels} in the core, the managed
 * taxonomy in an extension).
 */
public final class CategoryLabels {

    /** The fallback language: every kind should have an English label. */
    public static final String DEFAULT_LANGUAGE = "en";

    /** The languages a label can be written in — the languages the web app ships, in display order. */
    public static final List<String> LANGUAGES = List.of("en", "fr", "de", "ar", "es", "it", "nl", "pt");

    /** category → language → label, for the built-in kinds. */
    private static final Map<String, Map<String, String>> BUILT_IN = Map.ofEntries(
            entry("invoice", "Invoice", "Facture", "Rechnung", "فاتورة", "Factura", "Fattura", "Factuur", "Fatura"),
            entry("quote", "Quote", "Devis", "Angebot", "عرض سعر", "Presupuesto", "Preventivo", "Offerte", "Orçamento"),
            entry("contract", "Contract", "Contrat", "Vertrag", "عقد", "Contrato", "Contratto", "Contract", "Contrato"),
            entry("report", "Report", "Rapport", "Bericht", "تقرير", "Informe", "Relazione", "Rapport", "Relatório"),
            entry("letter", "Letter", "Courrier", "Brief", "رسالة", "Carta", "Lettera", "Brief", "Carta"),
            entry("cv", "CV", "CV", "Lebenslauf", "سيرة ذاتية", "Currículum", "Curriculum", "CV", "Currículo"),
            entry("presentation", "Presentation", "Présentation", "Präsentation", "عرض تقديمي", "Presentación", "Presentazione", "Presentatie", "Apresentação"),
            entry("spreadsheet", "Spreadsheet", "Tableur", "Tabelle", "جدول بيانات", "Hoja de cálculo", "Foglio di calcolo", "Spreadsheet", "Folha de cálculo"),
            entry("form", "Form", "Formulaire", "Formular", "نموذج", "Formulario", "Modulo", "Formulier", "Formulário"),
            entry("id-document", "ID document", "Pièce d’identité", "Ausweisdokument", "وثيقة هوية", "Documento de identidad", "Documento d’identità", "Identiteitsdocument", "Documento de identidade"),
            entry("receipt", "Receipt", "Reçu", "Beleg", "إيصال", "Recibo", "Ricevuta", "Bonnetje", "Recibo"),
            entry("minutes", "Minutes", "Compte rendu", "Protokoll", "محضر", "Acta", "Verbale", "Notulen", "Ata"),
            entry("specification", "Specification", "Spécification", "Spezifikation", "مواصفات", "Especificación", "Specifica", "Specificatie", "Especificação"),
            entry("manual", "Manual", "Manuel", "Handbuch", "دليل", "Manual", "Manuale", "Handleiding", "Manual"),
            entry(InsightResult.OTHER, "Other", "Autre", "Sonstiges", "أخرى", "Otro", "Altro", "Overig", "Outro"));

    private CategoryLabels() {
    }

    /** The shipped labels of a built-in kind, in {@link #LANGUAGES} order; empty for a kind OpenFilz does not ship. */
    public static Map<String, String> builtIn(String key) {
        return BUILT_IN.getOrDefault(CategoryTaxonomy.normalise(key), Map.of());
    }

    /** Every built-in kind's labels, {@code other} included. */
    public static Map<String, Map<String, String>> builtIn() {
        return BUILT_IN;
    }

    /**
     * A label map as it is stored: language codes lower case and restricted to {@link #LANGUAGES},
     * labels trimmed, blanks dropped, in {@link #LANGUAGES} order.
     */
    public static Map<String, String> normalise(Map<String, String> labels) {
        Map<String, String> out = new LinkedHashMap<>();
        if (labels == null) {
            return out;
        }
        Map<String, String> byLanguage = new LinkedHashMap<>();
        labels.forEach((language, label) -> {
            if (language != null && label != null && !label.isBlank()) {
                byLanguage.put(language.trim().toLowerCase(Locale.ROOT), label.trim());
            }
        });
        for (String language : LANGUAGES) {
            String label = byLanguage.get(language);
            if (label != null) {
                out.put(language, label);
            }
        }
        return out;
    }

    /** {@code overrides} on top of {@code base}, language by language (both normalised). */
    public static Map<String, String> merge(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>(normalise(base));
        merged.putAll(normalise(overrides));
        return normalise(merged);
    }

    /** The label in {@code language}, else the English one, else the key itself. */
    public static String resolve(Map<String, String> labels, String language, String key) {
        if (labels != null) {
            String label = language == null ? null : labels.get(language);
            if (label == null || label.isBlank()) {
                label = labels.get(DEFAULT_LANGUAGE);
            }
            if (label != null && !label.isBlank()) {
                return label;
            }
        }
        return key;
    }

    /**
     * The first supported language among the candidates, in order — each one a language code
     * ({@code fr}), a locale ({@code pt-BR}) or a whole {@code Accept-Language} header
     * ({@code de-CH,de;q=0.9,en;q=0.8}, read by weight); {@value #DEFAULT_LANGUAGE} when none is.
     */
    public static String resolveLanguage(String... candidates) {
        if (candidates != null) {
            for (String candidate : candidates) {
                String language = supported(candidate);
                if (language != null) {
                    return language;
                }
            }
        }
        return DEFAULT_LANGUAGE;
    }

    private static String supported(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        List<Locale.LanguageRange> ranges;
        try {
            ranges = Locale.LanguageRange.parse(candidate.trim().replace('_', '-'));
        } catch (IllegalArgumentException e) {
            return null;
        }
        // parse() answers the ranges by descending weight, header order kept for ties
        for (Locale.LanguageRange range : ranges) {
            String primary = range.getRange().split("-", 2)[0].toLowerCase(Locale.ROOT);
            if (range.getWeight() > 0 && LANGUAGES.contains(primary)) {
                return primary;
            }
        }
        return null;
    }

    private static Map.Entry<String, Map<String, String>> entry(String key, String... labels) {
        Map<String, String> byLanguage = new LinkedHashMap<>();
        for (int i = 0; i < LANGUAGES.size(); i++) {
            byLanguage.put(LANGUAGES.get(i), labels[i]);
        }
        return Map.entry(key, Collections.unmodifiableMap(byLanguage));
    }
}
