// com/example/dms/service/impl/AuditServiceImpl.java
package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.config.AuditProperties;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.audit.AuditVerificationResult.AuditVerificationStatus;
import org.openfilz.dms.dto.audit.AuditVerificationResult.BrokenLink;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.AuditDAO;
import org.openfilz.dms.service.AuditChainService;
import org.openfilz.dms.service.AuditService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditServiceImpl implements AuditService {

    private final AuditDAO auditDAO;
    private final AuditProperties auditProperties;
    private final AuditChainProperties chainProperties;
    private final AuditChainService auditChainService;

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details) {
        if (!isAuditable(action)) {
            return Mono.empty();
        }
        return auditDAO.logAction(action, resourceType, resourceId, details);
    }

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId) {
        if (!isAuditable(action)) {
            return Mono.empty();
        }
        return auditDAO.logAction(action, resourceType, resourceId, null);
    }

    private boolean isAuditable(AuditAction action) {
        var excluded = auditProperties.getExcludedActions();
        return excluded == null || !excluded.contains(action);
    }

    @Override
    public Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort) {
        return auditDAO.getAuditTrail(resourceId, sort == null ? SortOrder.DESC : sort);
    }

    @Override
    public Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request) {
        return auditDAO.searchAuditTrail(request);
    }

    @Override
    public Mono<AuditVerificationResult> verifyChain() {
        AtomicLong counter = new AtomicLong(0);
        AtomicReference<String> expectedPreviousHash = new AtomicReference<>();
        AtomicReference<BrokenLink> brokenLinkRef = new AtomicReference<>();

        return auditDAO.getChainedEntries()
                .takeWhile(_ -> brokenLinkRef.get() == null)
                .doOnNext(entry -> {
                    long position = counter.incrementAndGet();
                    String storedPreviousHash = entry.previousHash();
                    String storedHash = entry.hash();

                    // Check previous_hash linkage (skip for first entry)
                    if (expectedPreviousHash.get() != null && !expectedPreviousHash.get().equals(storedPreviousHash)) {
                        brokenLinkRef.set(new BrokenLink(position, expectedPreviousHash.get(), storedPreviousHash));
                        return;
                    }

                    // Recompute hash and verify
                    String recomputedHash = auditChainService.computeHash(
                            entry.timestamp(), entry.username(), entry.action(),
                            entry.resourceType(), entry.id(), entry.details(),
                            storedPreviousHash);

                    if (!recomputedHash.equals(storedHash)) {
                        brokenLinkRef.set(new BrokenLink(position, recomputedHash, storedHash));
                        return;
                    }

                    expectedPreviousHash.set(storedHash);
                })
                .then(Mono.fromCallable(() -> {
                    long total = counter.get();
                    BrokenLink brokenLink = brokenLinkRef.get();

                    if (total == 0) {
                        return new AuditVerificationResult(
                                AuditVerificationStatus.EMPTY, 0, 0,
                                OffsetDateTime.now(), null);
                    }

                    if (brokenLink != null) {
                        return new AuditVerificationResult(
                                AuditVerificationStatus.BROKEN, total, brokenLink.entryId() - 1,
                                OffsetDateTime.now(), brokenLink);
                    }

                    return new AuditVerificationResult(
                            AuditVerificationStatus.VALID, total, total,
                            OffsetDateTime.now(), null);
                }));
    }

    @Override
    public Mono<Void> initializeChainIfNeeded() {
        if (!chainProperties.isEnabled()) {
            log.info("Audit chain is disabled, skipping initialization");
            return Mono.empty();
        }

        return auditDAO.isChainInitialized()
                .flatMap(initialized -> {
                    if (initialized) {
                        log.info("Audit chain already initialized");
                        return Mono.empty();
                    }
                    log.info("Initializing audit chain with CHAIN_GENESIS entry");
                    return auditDAO.logAction(AuditAction.CHAIN_GENESIS, null, null, null);
                });
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        initializeChainIfNeeded()
                .doOnSuccess(_ -> log.info("Audit chain initialization check completed"))
                .doOnError(e -> log.error("Failed to initialize audit chain: {}", e.getMessage()))
                .subscribe();
    }
}
