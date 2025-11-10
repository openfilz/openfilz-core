// com/example/dms/service/AuditService.java
package org.openfilz.dms.service;

import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

public interface AuditService {
    Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, AuditLogDetails details);

    Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId);

    Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort);

    Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request);
}