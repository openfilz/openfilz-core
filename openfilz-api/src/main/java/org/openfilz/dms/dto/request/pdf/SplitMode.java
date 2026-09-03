package org.openfilz.dms.dto.request.pdf;

/** How a PDF is cut into parts. */
public enum SplitMode {
    /** Parts of {@code n} pages each (the last one may be shorter). */
    EVERY_N_PAGES,
    /** One part per page. */
    EVERY_PAGE,
    /** A new part starts at each of the given pages (2..pageCount). */
    AT_PAGES,
    /** One part per explicit page selection, e.g. {@code ["1-3", "4-10", "11-"]}. */
    PAGE_RANGES,
    /** A new part starts at every bookmark (outline entry) of level {@code outlineLevel} or above. */
    BY_OUTLINE_LEVEL
}
