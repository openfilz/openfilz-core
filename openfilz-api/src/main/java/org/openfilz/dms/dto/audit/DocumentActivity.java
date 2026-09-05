package org.openfilz.dms.dto.audit;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One document's activity as the audit trail records it: when it was last touched, how many
 * actions it saw, by how many distinct users. Computed for many documents in one grouped query.
 */
public record DocumentActivity(UUID documentId, OffsetDateTime lastAt, long actions, long actors) {
}
