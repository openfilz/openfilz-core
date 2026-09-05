package org.openfilz.dms.service.insight;

import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.springframework.ai.embedding.EmbeddingModel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Categories by prototype: one short multilingual description per category is embedded once,
 * the document's head is embedded with the same model, and the nearest description wins. No
 * generation, no chat model, one embedding call per document — a few tens of milliseconds on a
 * CPU with the embedding model the deployment already runs for search.
 * <p>
 * The confidence is the softmax of the cosine similarities at {@code temperature}: the closer the
 * runner-up, the lower it. Below {@code min-similarity} nothing fits and the answer is
 * {@value InsightResult#OTHER}. Coarse kinds (invoice / report / contract) separate well; fine
 * ones (supplier vs customer invoice) do not — that is the neighbour vote's job.
 */
@Slf4j
public class PrototypeCategoryClassifier implements CategoryClassifier {

    /**
     * What each default category looks like, in the languages OpenFilz ships: the embedding
     * model reads the description, so it names the words a document of that kind carries.
     * Overridden per key by {@code openfilz.ai.insights.classifier.prototypes}.
     */
    public static final Map<String, String> DEFAULT_PROTOTYPES = Map.ofEntries(
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
                    + "step by step. Manuel d'utilisation, notice, mode d'emploi, guide. Handbuch, Bedienungsanleitung."));

    private final EmbeddingModel embeddingModel;
    private final String name;
    private final List<String> categories;
    private final List<String> descriptions;
    private final double temperature;
    private final double minSimilarity;
    private final int maxChars;
    private final String prefix;
    private volatile float[][] prototypeVectors;

    /**
     * @param embeddingModel the deployment's embedding model (the one the vector store uses)
     * @param modelName      the embedding model's id, for {@link #name()}
     * @param categories     the closed category list; {@value InsightResult#OTHER} gets no prototype
     * @param config         mode-independent settings: temperature, floor, text head, prefix, prototype overrides
     */
    public PrototypeCategoryClassifier(EmbeddingModel embeddingModel, String modelName, List<String> categories,
                                       AiProperties.Insights.Classifier config) {
        this.embeddingModel = embeddingModel;
        this.name = "prototype:" + (modelName == null || modelName.isBlank() ? "embedding" : modelName);
        this.temperature = config.getTemperature() > 0 ? config.getTemperature() : 0.02;
        this.minSimilarity = config.getMinSimilarity();
        this.maxChars = Math.max(200, config.getMaxChars());
        this.prefix = config.getPrefix() == null ? "" : config.getPrefix();
        Map<String, String> prototypes = prototypes(categories, config.getPrototypes());
        this.categories = List.copyOf(prototypes.keySet());
        this.descriptions = List.copyOf(prototypes.values());
    }

    /** The categories that carry a prototype, in order: every listed one but {@value InsightResult#OTHER}. */
    static Map<String, String> prototypes(List<String> categories, Map<String, String> overrides) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> listed = categories == null || categories.isEmpty() ? List.copyOf(DEFAULT_PROTOTYPES.keySet()) : categories;
        for (String raw : listed) {
            if (raw == null) continue;
            String category = raw.trim().toLowerCase(Locale.ROOT);
            if (category.isEmpty() || InsightResult.OTHER.equals(category)) continue;
            String override = overrides == null ? null : overrides.get(category);
            String description = override != null && !override.isBlank() ? override : DEFAULT_PROTOTYPES.get(category);
            // A category nobody described is still a word the model can place
            out.put(category, description == null || description.isBlank() ? category.replace('-', ' ') : description);
        }
        return out;
    }

    @Override
    public String name() {
        return name;
    }

    /** The categories that carry a prototype, in the configured order. */
    public List<String> categories() {
        return categories;
    }

    @Override
    public CategoryPrediction classify(String fileName, String text) {
        return decide(similarities(fileName, text), temperature, minSimilarity);
    }

    /**
     * Every prototype category with its cosine similarity to the document, best first. One
     * embedding call; the benchmark reuses it to score several temperatures at once.
     */
    public List<CategoryPrediction.Scored> similarities(String fileName, String text) {
        if (categories.isEmpty()) {
            return List.of();
        }
        float[][] prototypes = prototypeVectors();
        float[] vector = embeddingModel.embed(input(fileName, text));
        List<CategoryPrediction.Scored> scored = new ArrayList<>(categories.size());
        for (int i = 0; i < categories.size(); i++) {
            scored.add(new CategoryPrediction.Scored(categories.get(i), cosine(vector, prototypes[i])));
        }
        scored.sort(Comparator.comparingDouble(CategoryPrediction.Scored::score).reversed());
        return scored;
    }

    /**
     * The verdict for sorted similarities: the best category, its softmax share at
     * {@code temperature} as the confidence, {@value InsightResult#OTHER} below {@code minSimilarity}.
     */
    public static CategoryPrediction decide(List<CategoryPrediction.Scored> sorted, double temperature, double minSimilarity) {
        if (sorted == null || sorted.isEmpty()) {
            return new CategoryPrediction(InsightResult.OTHER, 0, List.of());
        }
        double t = temperature > 0 ? temperature : 0.02;
        double best = sorted.getFirst().score();
        double sum = 0;
        for (CategoryPrediction.Scored s : sorted) {
            sum += Math.exp((s.score() - best) / t);
        }
        double confidence = 1 / sum;
        String category = best >= minSimilarity ? sorted.getFirst().category() : InsightResult.OTHER;
        return new CategoryPrediction(category, confidence, sorted);
    }

    String input(String fileName, String text) {
        String body = text == null ? "" : text.length() > maxChars ? text.substring(0, maxChars) : text;
        String name = fileName == null ? "" : fileName;
        return prefix + (name.isEmpty() ? "" : "File name: " + name + "\n") + body;
    }

    private float[][] prototypeVectors() {
        float[][] current = prototypeVectors;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (prototypeVectors == null) {
                List<String> inputs = descriptions.stream().map(d -> prefix + d).toList();
                List<float[]> vectors = embeddingModel.embed(inputs);
                if (vectors.size() != descriptions.size()) {
                    throw new IllegalStateException("the embedding model returned " + vectors.size()
                            + " vectors for " + descriptions.size() + " prototypes");
                }
                prototypeVectors = vectors.toArray(new float[0][]);
                log.info("[INSIGHTS] {} prototype(s) embedded for the category classifier ({})", vectors.size(), name);
            }
            return prototypeVectors;
        }
    }

    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return 0;
        }
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
