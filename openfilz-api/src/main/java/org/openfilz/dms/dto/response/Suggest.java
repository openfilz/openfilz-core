package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * Represents a single search suggestion, linking the suggested text
 * to the unique ID of the document.
 *
 * @param id The UUID of the document.
 * @param s The highlighted suggestion text.
 * @param ext The extension of the file or null if it is a folder
 * @param contentSnippet A highlighted excerpt from the document content, or null if the match was name-only
 */
public record Suggest(UUID id, String s, String ext, String contentSnippet) {}