package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Ask for the by-kind split of a scope: every folder under {@code rootFolderId} (the root level
 * when null) holding documents of several kinds gets one sub-folder per kind. The answer is a
 * stored, reviewable reorganisation plan — nothing moves until it is applied.
 *
 * @param rootFolderId the scope; null for the root level
 * @param language     the language to name new folders in when the existing folder names do not tell
 *                     (the user's UI language, e.g. {@code fr}); absent = the deployment default
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReorganizationByKindRequest(UUID rootFolderId, String language) {

    public ReorganizationByKindRequest(UUID rootFolderId) {
        this(rootFolderId, null);
    }
}
