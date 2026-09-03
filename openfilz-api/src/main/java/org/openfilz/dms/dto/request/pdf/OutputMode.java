package org.openfilz.dms.dto.request.pdf;

/** Where the result of a PDF operation goes. */
public enum OutputMode {
    /** Create a new sibling document (the sources are left untouched). */
    NEW_DOCUMENT,
    /** Replace the content of the (first) source document — a new version when versioning is on. */
    NEW_VERSION
}
