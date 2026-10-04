package org.openfilz.dms.service.insight;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The local classifier as the deployment runs it: two rules in front of the embedding one.
 * <ol>
 *   <li>A file name that names its kind outright ({@link FileNameKindHints}: {@code CNI recto.jpg},
 *       {@code Facture 2026-03.pdf}) is that kind, whatever the text says.</li>
 *   <li>A file with no text (a photo, a scan without OCR, an unreadable file) and no such name is
 *       {@value InsightResult#OTHER}: embedding a bare file name lands on a near-random kind, and a
 *       wrong kind is worse than none — it sends the file to the wrong folder.</li>
 * </ol>
 * Everything else goes to the delegate. The name is the delegate's followed by {@link #NAME_MARK}
 * ({@code prototype:…+names}, {@code learned:knn+names}): every row this classifier writes — at
 * upload, in a backfill, on demand — says the rules were applied, so {@link #namedWithoutRules}
 * tells the rows a local classifier guessed before them.
 */
public class FileNameAwareCategoryClassifier implements CategoryClassifier {

    /** Ends the name of a local classifier that ran behind the file-name rules. */
    public static final String NAME_MARK = "+names";

    private final CategoryClassifier delegate;
    private final CategoryTaxonomy taxonomy;

    public FileNameAwareCategoryClassifier(CategoryClassifier delegate, CategoryTaxonomy taxonomy) {
        this.delegate = delegate;
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return delegate.name() + NAME_MARK;
    }

    /**
     * Whether a stored row's {@code model} is a local classifier's ({@code prototype:} /
     * {@code learned:}) from before the file-name rules: its kind may be a guess from a bare name.
     */
    public static boolean namedWithoutRules(String model) {
        LearnedCategoryClassifier.Source source = LearnedCategoryClassifier.Source.of(model);
        return (source == LearnedCategoryClassifier.Source.PROTOTYPE || source == LearnedCategoryClassifier.Source.LEARNED)
                && !model.trim().endsWith(NAME_MARK);
    }

    @Override
    public CategoryPrediction classify(UUID documentId, String fileName, String text) {
        Set<String> kinds = new LinkedHashSet<>(taxonomy.keys());
        kinds.remove(InsightResult.OTHER);
        Optional<String> named = FileNameKindHints.kindOf(fileName, kinds);
        if (named.isPresent()) {
            return new CategoryPrediction(named.get(), 1.0, List.of(new CategoryPrediction.Scored(named.get(), 1.0)));
        }
        if (text == null || text.isBlank()) {
            return new CategoryPrediction(InsightResult.OTHER, 0, List.of());
        }
        return delegate.classify(documentId, fileName, text);
    }
}
