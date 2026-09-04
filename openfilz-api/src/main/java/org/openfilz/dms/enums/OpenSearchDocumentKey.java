package org.openfilz.dms.enums;

public enum OpenSearchDocumentKey {
    id,
    name,
    name_suggest,
    extension,
    size,
    parentId,
    createdAt,
    updatedAt,
    createdBy,
    updatedBy,
    content,
    metadata,
    active,
    /** Tier-2 document insight (keyword): one of openfilz.ai.insights.categories. */
    category,
    /** Tier-2 document insight (text). */
    summary,
    /** Document insight (keyword): BCP-47 primary tag. */
    language
}
