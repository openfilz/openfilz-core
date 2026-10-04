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
 * Everything else goes to the delegate. The name is the delegate's, so the stored rows read as
 * before ({@code prototype:…}, {@code learned:knn}).
 */
public class FileNameAwareCategoryClassifier implements CategoryClassifier {

    private final CategoryClassifier delegate;
    private final CategoryTaxonomy taxonomy;

    public FileNameAwareCategoryClassifier(CategoryClassifier delegate, CategoryTaxonomy taxonomy) {
        this.delegate = delegate;
        this.taxonomy = taxonomy;
    }

    @Override
    public String name() {
        return delegate.name();
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
