package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.service.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.audit.chain.verification-enabled", havingValue = "true", matchIfMissing = true)
public class AuditVerificationScheduler {

    private final AuditService auditService;

    @Scheduled(cron = "${openfilz.audit.chain.verification-cron:0 0 3 * * ?}")
    public void verifyAuditChain() {
        log.info("Starting scheduled audit chain verification");
        auditService.verifyChain()
                .doOnNext(result -> {
                    switch (result.status()) {
                        case VALID -> log.info("Audit chain verification passed: {} entries verified", result.verifiedEntries());
                        case BROKEN -> {
                            AuditVerificationResult.BrokenLink link = result.brokenLink();
                            log.warn("AUDIT CHAIN INTEGRITY VIOLATION: Chain broken at entry {}. Expected hash: {}, actual: {}",
                                    link.entryId(), link.expectedHash(), link.actualHash());
                        }
                        case EMPTY -> log.info("Audit chain verification: no chained entries found");
                    }
                })
                .doOnError(e -> log.error("Audit chain verification failed: {}", e.getMessage()))
                .subscribe();
    }
}
