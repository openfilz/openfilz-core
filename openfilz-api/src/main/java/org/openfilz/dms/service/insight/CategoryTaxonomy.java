package org.openfilz.dms.service.insight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The kinds of documents a deployment knows — the closed list the tier-2 category is drawn from,
 * with what each kind looks like. One source for everything that reads the list: the enrichment
 * prompt (the descriptions tell the model what the keys mean), the prototype classifier (the
 * descriptions are what it embeds), the settings the web app offers in the kind editor, the
 * validation of a user's correction.
 * <p>
 * The core reads it from the properties ({@link PropertiesCategoryTaxonomy}); an extension
 * registers a {@code @Primary} taxonomy managed elsewhere, which may change at runtime — readers
 * must not cache the list beyond one operation (the prototype classifier re-embeds its
 * descriptions when the content changes).
 */
public interface CategoryTaxonomy {

    /**
     * One kind.
     *
     * @param key         the stored value: lower case, hyphens ({@code id-document})
     * @param description what a document of that kind looks like, in the languages the deployment
     *                    handles; empty when nobody described it
     * @param examples    typical file names or phrases; empty when none
     * @param labels      display name per language ({@link CategoryLabels#LANGUAGES}), English the fallback;
     *                    empty when nobody named the kind (it then shows as its key)
     */
    record Category(String key, String description, List<String> examples, Map<String, String> labels) {

        public Category {
            key = normalise(key);
            description = description == null ? "" : description.trim();
            examples = examples == null ? List.of() : List.copyOf(examples.stream().filter(e -> e != null && !e.isBlank()).toList());
            labels = Collections.unmodifiableMap(CategoryLabels.normalise(labels));
        }

        /** A kind named by the built-in labels (none for a kind OpenFilz does not ship). */
        public Category(String key, String description, List<String> examples) {
            this(key, description, examples, CategoryLabels.builtIn(key));
        }

        public Category(String key, String description) {
            this(key, description, List.of());
        }

        /** The display name in {@code language}, else the English one, else the key. */
        public String label(String language) {
            return CategoryLabels.resolve(labels, language, key);
        }
    }

    /**
     * What each built-in category looks like, in the languages OpenFilz ships: the embedding
     * model reads the description, so it names the words a document of that kind carries, and
     * the chat model reads it to know what the key means.
     */
    Map<String, String> BUILT_IN_DESCRIPTIONS = Map.ofEntries(
            Map.entry("invoice", "An invoice: a bill requesting payment for goods or services, with an invoice number, "
                    + "amounts, VAT, the total due and payment terms. Facture, montant HT et TTC, TVA, échéance de paiement. "
                    + "Rechnung, Betrag, MwSt."),
            Map.entry("quote", "A quote or estimate proposing prices for goods or services before an order, valid until a date. "
                    + "Devis, proposition de prix, validité de l'offre. Angebot, Kostenvoranschlag."),
            Map.entry("contract", "A contract or agreement between parties: clauses, obligations, terms, duration, liability, "
                    + "signatures. Contrat, convention, accord, conditions générales, parties, signature. Vertrag, Vereinbarung."),
            Map.entry("report", "A report: analysis, findings, figures, results and conclusions on a subject or a period. "
                    + "Rapport, bilan, compte rendu d'activité, résultats, synthèse. Bericht, Ergebnisse."),
            Map.entry("letter", "A letter or formal correspondence addressed to a person or an organisation, with a date, "
                    + "a salutation and a signature. Courrier, lettre, madame, monsieur, veuillez agréer. Brief, Schreiben."),
            Map.entry("cv", "A curriculum vitae or résumé: a person's work experience, education, skills and languages. "
                    + "CV, curriculum vitae, parcours professionnel, formation, compétences. Lebenslauf, Berufserfahrung."),
            Map.entry("presentation", "A slide deck or presentation: slide titles, bullet points, an agenda, a closing slide. "
                    + "Présentation, diapositives, sommaire. Präsentation, Folien."),
            Map.entry("spreadsheet", "A spreadsheet or table of data: rows and columns of figures, sheets, totals, cells. "
                    + "Tableur, feuille de calcul, tableau de chiffres, colonnes. Tabelle, Kalkulation."),
            Map.entry("form", "A form to fill in: fields, checkboxes, applicant details, declarations, date and signature boxes. "
                    + "Formulaire, demande à compléter, cocher la case. Formular, Antrag."),
            Map.entry("id-document", "An identity document: passport, identity card or driving licence with a name, a date of birth, "
                    + "a number and an expiry date. Pièce d'identité, carte nationale d'identité, passeport, permis de conduire. "
                    + "Ausweis, Reisepass."),
            Map.entry("receipt", "A receipt or proof of payment for a purchase: items, amount paid, date, merchant, card. "
                    + "Reçu, ticket de caisse, justificatif de paiement, montant réglé. Quittung, Kassenbon, Beleg."),
            Map.entry("minutes", "Minutes of a meeting: attendees, agenda, discussion, decisions taken, actions and owners. "
                    + "Compte rendu de réunion, procès-verbal, participants, décisions, ordre du jour. Protokoll, Sitzung."),
            Map.entry("specification", "A specification or requirements document: features, functional and technical "
                    + "requirements, architecture, constraints. Cahier des charges, spécifications fonctionnelles et techniques. "
                    + "Spezifikation, Anforderungen, Lastenheft."),
            Map.entry("manual", "A user manual, guide or instructions: how to install, configure, use or operate a product, "
                    + "step by step. Manuel d'utilisation, notice, mode d'emploi, guide. Handbuch, Bedienungsanleitung."),
            Map.entry(InsightResult.OTHER, "Anything that fits none of the other kinds."));

    /** Enabled categories in display order; always ends with {@value InsightResult#OTHER}. */
    List<Category> categories();

    /** The keys of {@link #categories()}, in the same order. */
    default List<String> keys() {
        return categories().stream().map(Category::key).toList();
    }

    /**
     * The display name of every enabled kind in {@code language} (English when the kind has no
     * label in it, the key when it has none at all), in {@link #categories()} order.
     */
    default Map<String, String> labels(String language) {
        Map<String, String> labels = new LinkedHashMap<>();
        categories().forEach(category -> labels.put(category.key(), category.label(language)));
        return labels;
    }

    /** The category a user or a model named, normalised like the stored value; empty when the taxonomy has no such kind. */
    default Optional<Category> find(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        String wanted = normalise(key);
        return categories().stream().filter(category -> category.key().equals(wanted)).findFirst();
    }

    /** The stored spelling of a key: lower case, spaces and underscores as hyphens. */
    static String normalise(String key) {
        return key == null ? "" : key.trim().toLowerCase(Locale.ROOT).replace(' ', '-').replace('_', '-');
    }
}
