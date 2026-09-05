package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Ask for the by-kind split of a scope: every folder under {@code rootFolderId} (the root level
 * when null) holding documents of several kinds gets one sub-folder per kind. The answer is a
 * stored, reviewable reorganisation plan — nothing moves until it is applied.
 *
 * @param rootFolderId the scope; null for the root level
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReorganizationByKindRequest(UUID rootFolderId) {
}
