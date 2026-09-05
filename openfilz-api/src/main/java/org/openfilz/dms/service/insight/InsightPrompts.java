package org.openfilz.dms.service.insight;

import java.util.List;
import java.util.Locale;

/**
 * The tier-2 prompt, shared by the enrichment worker and the category benchmark so both ask
 * the model the same thing. Bump {@link AiDocumentInsightService#PROMPT_VERSION} when it changes.
 */
public final class InsightPrompts {

    private InsightPrompts() {
    }

    public static String system(String marker, List<String> categories) {
        return """
                You label documents for a document management system (%s).
                Given the beginning of a document's text and its file name, answer with ONE JSON object and nothing else:
                {"category": "<one of: %s>", "summary": "<one or two sentences, in the document's own language>", \
                "keywords": ["<up to 8 short keywords>"], "language": "<BCP-47 primary tag, e.g. fr>", \
                "entities": {"<key>": "<value>"}}
                Rules: the category MUST be one of the listed values ("other" when none fits); entities are the few \
                identifiers that matter for filing the document (client, supplier, invoice_number, contract_reference, \
                period, project, person) — omit what is absent; never invent facts; no Markdown, no commentary.
                """.formatted(marker, String.join(", ", categories == null ? List.of(InsightResult.OTHER) : categories));
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
