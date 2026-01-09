// com/example/dms/service/impl/AuditServiceImpl.java
package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.AuditDAO;
import org.openfilz.dms.service.AuditService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditDAO auditDAO;

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details) {
        return auditDAO.logAction(action, resourceType, resourceId, details);
    }

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId) {
        return auditDAO.logAction(action, resourceType, resourceId, null);
    }

    @Override
    public Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort) {
        return auditDAO.getAuditTrail(resourceId, sort == null ? SortOrder.DESC : sort);
    }

    @Override
    public Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request) {
        return auditDAO.searchAuditTrail(request);
    }

}