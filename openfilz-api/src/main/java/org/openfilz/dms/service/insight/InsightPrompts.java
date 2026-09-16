package org.openfilz.dms.service.insight;

import org.openfilz.dms.service.insight.CategoryTaxonomy.Category;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The tier-2 prompt, shared by the enrichment worker and the category benchmark so both ask
 * the model the same thing. Bump {@link AiDocumentInsightService#PROMPT_VERSION} when it changes.
 */
public final class InsightPrompts {

    private InsightPrompts() {
    }

    /**
     * The system prompt: the JSON contract, then the kinds with what each one looks like — a
     * bare key ({@code minutes}, {@code form}) is ambiguous to a model, its description is not,
     * and an extension's taxonomy carries kinds no model has seen. The marker and the JSON
     * contract are what the callers key on; the kind list is the only part that varies.
     */
    public static String system(String marker, List<Category> categories) {
        List<Category> kinds = categories == null || categories.isEmpty()
                ? List.of(new Category(InsightResult.OTHER, CategoryTaxonomy.BUILT_IN_DESCRIPTIONS.get(InsightResult.OTHER)))
                : categories;
        String keys = kinds.stream().map(Category::key).collect(Collectors.joining(", "));
        return """
                You label documents for a document management system (%s).
                Given the beginning of a document's text and its file name, answer with ONE JSON object and nothing else:
                {"category": "<one of: %s>", "summary": "<one or two sentences, in the document's own language>", \
                "keywords": ["<up to 8 short keywords>"], "language": "<BCP-47 primary tag, e.g. fr>", \
                "entities": {"<key>": "<value>"}}
                The kinds:
                %s
                Rules: the category MUST be one of the listed values ("other" when none fits); entities are the few \
                identifiers that matter for filing the document (client, supplier, invoice_number, contract_reference, \
                period, project, person) — omit what is absent; never invent facts; no Markdown, no commentary.
                """.formatted(marker, keys, kinds.stream().map(InsightPrompts::describe).collect(Collectors.joining("\n")));
    }

    /** One line per kind: {@code - key — description (e.g. example, example)}; a kind nobody described is its key alone. */
    static String describe(Category category) {
        StringBuilder line = new StringBuilder("- ").append(category.key());
        if (!category.description().isEmpty()) {
            line.append(" — ").append(category.description());
        }
        if (!category.examples().isEmpty()) {
            line.append(" (e.g. ").append(String.join(", ", category.examples())).append(')');
        }
        return line.toString();
    }

    public static String user(String fileName, String contentType, String text) {
        String name = fileName == null ? "" : fileName;
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return "File name: " + name + "\nType: " + (extension.isEmpty() ? "unknown" : extension)
                + (contentType != null ? " (" + contentType + ")" : "")
                + "\n\nText (beginning):\n" + text;
    }
}
