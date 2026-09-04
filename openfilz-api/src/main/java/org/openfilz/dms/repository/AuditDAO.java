package org.openfilz.dms.repository;

import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AuditDAO {
    Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details);
    Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort);

    /**
     * Last action, action count and distinct actors of each of {@code documentIds}, in one
     * grouped query (documents without any entry are absent). Feeds the reorganisation inventory.
     */
    Flux<org.openfilz.dms.dto.audit.DocumentActivity> activitySummary(java.util.Collection<UUID> documentIds);
    Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request);
    Mono<String> getLastHash();
    Mono<Boolean> isChainInitialized();
    Flux<AuditLog> getChainedEntries();
    Flux<AuditLog> getChainedEntriesInRange(long fromId, long toId);
}
