package org.openfilz.dms.service.pdf;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parses the page selection syntax shared by the REST API, the AI tools and the UI:
 * <pre>
 *   3            a single page
 *   2-5          an inclusive range
 *   4-           from page 4 to the end
 *   -3           from page 1 to page 3
 *   1-3,7,10-    several, comma (or space) separated — order and duplicates are kept
 *   all | odd | even
 * </pre>
 * Page numbers are 1-based and validated against the document's page count. A {@code null} or blank
 * selection means every page.
 */
public final class PageRangeParser {

    private PageRangeParser() {
    }

    /**
     * @param spec      the selection, or null/blank for every page
     * @param pageCount pages in the document (must be &gt; 0)
     * @return the selected pages, 1-based, in selection order (duplicates kept)
     * @throws IllegalArgumentException on a malformed token or a page outside 1..pageCount
     */
    public static List<Integer> parse(String spec, int pageCount) {
        if (pageCount < 1) {
            throw new IllegalArgumentException("The document has no pages");
        }
        List<Integer> pages = new ArrayList<>();
        if (spec == null || spec.isBlank()) {
            addRange(pages, 1, pageCount);
            return pages;
        }
        for (String rawToken : spec.split("[,\\s]+")) {
            String token = rawToken.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "all" -> addRange(pages, 1, pageCount);
                case "odd" -> {
                    for (int p = 1; p <= pageCount; p += 2) pages.add(p);
                }
                case "even" -> {
                    for (int p = 2; p <= pageCount; p += 2) pages.add(p);
                }
                default -> parseToken(token, pageCount, pages);
            }
        }
        if (pages.isEmpty()) {
            throw new IllegalArgumentException("Empty page selection: '" + spec + "'");
        }
        return pages;
    }

    private static void parseToken(String token, int pageCount, List<Integer> pages) {
        int dash = token.indexOf('-');
        if (dash < 0) {
            pages.add(parsePage(token, pageCount, token));
            return;
        }
        String left = token.substring(0, dash).trim();
        String right = token.substring(dash + 1).trim();
        if (right.indexOf('-') >= 0) {
            throw malformed(token);
        }
        int from = left.isEmpty() ? 1 : parsePage(left, pageCount, token);
        int to = right.isEmpty() ? pageCount : parsePage(right, pageCount, token);
        if (from > to) {
            throw new IllegalArgumentException("Invalid page range '" + token + "': start is after end");
        }
        addRange(pages, from, to);
    }

    private static int parsePage(String number, int pageCount, String token) {
        int page;
        try {
            page = Integer.parseInt(number);
        } catch (NumberFormatException e) {
            throw malformed(token);
        }
        if (page < 1 || page > pageCount) {
            throw new IllegalArgumentException("Page " + page + " is out of range (the document has "
                    + pageCount + " page" + (pageCount > 1 ? "s" : "") + ")");
        }
        return page;
    }

    private static IllegalArgumentException malformed(String token) {
        return new IllegalArgumentException("Invalid page selection '" + token
                + "': use page numbers and ranges such as 1-3,7,10-");
    }

    private static void addRange(List<Integer> pages, int from, int to) {
        for (int p = from; p <= to; p++) {
            pages.add(p);
        }
    }
}
