package org.openfilz.dms.event;

import java.util.UUID;

/**
 * Published (Spring application event) once the text of a file has been extracted and sent to the
 * full-text index, after an upload or a content replacement. Core publishes it and nothing more;
 * extension layers may react to it.
 * <p>
 * The event does not carry the text: it is streamed to the index and never held in memory as a whole.
 * A listener that needs it reads it back from the index ({@code IndexService.getContent}).
 * <p>
 * Not published when the full-text search is off, nor when the indexing failed. A future bulk
 * re-indexing must not publish it: it says that a document's content arrived, not that it was re-read.
 * A listener runs on the indexing thread and must hand its work off at once; an exception it throws
 * never fails the indexing.
 *
 * @param documentId   the document
 * @param name         its name when it was indexed
 * @param indexedChars characters of text extracted (before the index cap)
 * @param truncated    whether the text was longer than what the index keeps
 */
public record DocumentTextExtractedEvent(UUID documentId, String name, int indexedChars, boolean truncated) {
}
