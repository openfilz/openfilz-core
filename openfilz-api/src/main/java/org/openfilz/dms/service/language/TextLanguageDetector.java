package org.openfilz.dms.service.language;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Guesses the language of a text among the languages the OpenFilz UI ships (en, fr, de, es, it,
 * nl, pt, ar), with no model and no dependency: Arabic by script, the others by their most
 * frequent function words. A word shared by several lists counts for each of them by
 * {@code 1 / number of lists}, so "de" or "la" barely weigh while "the", "und" or "est" decide.
 * <p>
 * Function words make up 30–50 % of running text in these languages, so a few hundred words are
 * enough. Too little text, or two languages too close, answers empty rather than guessing.
 */
public final class TextLanguageDetector {

    /** Below this many words the guess is refused. */
    static final int MIN_WORDS = 20;
    /** Below this many (weighted) function-word hits the guess is refused. */
    static final double MIN_HITS = 5;
    /** The winner must hold at least this share of the two best scores. */
    static final double MIN_CONFIDENCE = 0.6;

    public record Detection(String language, double confidence) {}

    private static final Map<String, Set<String>> STOPWORDS = Map.of(
            "en", Set.of("the", "and", "of", "to", "is", "that", "for", "it", "with", "as", "was", "on", "are",
                    "be", "this", "by", "from", "at", "or", "an", "have", "not", "which", "you", "we", "they",
                    "their", "will", "can", "has", "been", "would", "there", "what", "more", "if", "our",
                    "about", "these", "should"),
            "fr", Set.of("le", "la", "les", "de", "des", "du", "et", "est", "un", "une", "en", "que", "qui",
                    "pour", "dans", "sur", "pas", "par", "au", "aux", "ce", "cette", "avec", "sont", "ne", "se",
                    "il", "elle", "nous", "vous", "ils", "mais", "ou", "leur", "plus", "être", "à", "son",
                    "sa", "ses", "était"),
            "de", Set.of("der", "die", "das", "und", "ist", "nicht", "ein", "eine", "zu", "den", "dem", "mit",
                    "von", "sich", "des", "auf", "für", "im", "auch", "es", "an", "werden", "wird", "sind", "als",
                    "bei", "nach", "wie", "oder", "aber", "vor", "zur", "zum", "durch", "kann", "wir", "sie",
                    "ich", "noch", "einer"),
            "es", Set.of("el", "la", "los", "las", "de", "del", "y", "que", "en", "un", "una", "es", "por",
                    "con", "para", "se", "no", "su", "al", "lo", "como", "más", "pero", "sus", "le", "ha",
                    "este", "esta", "son", "entre", "cuando", "muy", "sin", "sobre", "también", "fue", "hay",
                    "o", "nos", "porque"),
            "it", Set.of("il", "lo", "la", "gli", "le", "di", "del", "della", "e", "è", "che", "un", "una",
                    "per", "non", "con", "si", "da", "in", "sono", "al", "alla", "come", "più", "ma", "anche",
                    "questo", "questa", "nel", "nella", "dei", "delle", "ha", "essere", "se", "suo", "sua",
                    "loro", "tra", "perché"),
            "nl", Set.of("de", "het", "een", "en", "van", "is", "dat", "op", "te", "in", "voor", "niet", "met",
                    "zijn", "er", "aan", "ook", "als", "bij", "door", "maar", "om", "of", "dan", "wordt",
                    "worden", "hij", "ze", "we", "deze", "dit", "die", "naar", "uit", "nog", "kan", "heeft",
                    "wat", "meer", "over"),
            "pt", Set.of("o", "a", "os", "as", "de", "do", "da", "dos", "das", "e", "que", "em", "um", "uma",
                    "para", "com", "não", "no", "na", "por", "se", "é", "mais", "ao", "como", "mas", "foi",
                    "ele", "ela", "são", "seu", "sua", "ou", "nos", "nas", "também", "pelo", "pela", "já",
                    "está"));

    private static final Map<String, Double> WEIGHTS = weights();

    private TextLanguageDetector() {
    }

    public static Optional<Detection> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        long arabic = text.codePoints().filter(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.ARABIC).count();
        long letters = text.codePoints().filter(Character::isLetter).count();
        if (letters >= MIN_WORDS * 3 && arabic * 2 > letters) {
            return Optional.of(new Detection("ar", Math.min(1.0, (double) arabic / letters)));
        }
        String[] words = text.toLowerCase(Locale.ROOT).split("[^\\p{L}]+");
        Map<String, Double> scores = new HashMap<>();
        int count = 0;
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            count++;
            Double weight = WEIGHTS.get(word);
            if (weight == null) {
                continue;
            }
            for (Map.Entry<String, Set<String>> language : STOPWORDS.entrySet()) {
                if (language.getValue().contains(word)) {
                    scores.merge(language.getKey(), weight, Double::sum);
                }
            }
        }
        if (count < MIN_WORDS) {
            return Optional.empty();
        }
        List<Map.Entry<String, Double>> ranked = scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .toList();
        if (ranked.isEmpty() || ranked.getFirst().getValue() < MIN_HITS) {
            return Optional.empty();
        }
        double best = ranked.getFirst().getValue();
        double second = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        double confidence = best / (best + second);
        if (confidence < MIN_CONFIDENCE) {
            return Optional.empty();
        }
        return Optional.of(new Detection(ranked.getFirst().getKey(), confidence));
    }

    private static Map<String, Double> weights() {
        Map<String, Integer> lists = new HashMap<>();
        STOPWORDS.values().forEach(words -> words.forEach(w -> lists.merge(w, 1, Integer::sum)));
        Map<String, Double> weights = new HashMap<>();
        lists.forEach((word, n) -> weights.put(word, 1.0 / n));
        return Map.copyOf(weights);
    }
}
