package org.openfilz.dms.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A user's correction of a document's kind: one of the deployment's categories (or {@code other}).
 * Stored as a tier-2 insight written by {@code user}, it teaches the learned classifier and
 * drives the by-kind reorganisation like a model's label would.
 *
 * @param category the kind, case-insensitive, spaces or underscores accepted for hyphens
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InsightCategoryUpdate(String category) {
}
