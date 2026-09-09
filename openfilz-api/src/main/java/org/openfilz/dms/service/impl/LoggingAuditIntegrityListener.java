package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.service.AuditIntegrityListener;
import org.openfilz.dms.service.AuditIntegrityStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default {@link AuditIntegrityListener}: records the verdict in {@link AuditIntegrityStatus}
 * and logs it at a severity that matches what it means.
 *
 * <p>A deployment with a real alerting channel overrides this with a {@code @Primary} bean —
 * it should still update {@link AuditIntegrityStatus}, since that is what the API exposes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LoggingAuditIntegrityListener implements AuditIntegrityListener {

    private final AuditIntegrityStatus status;

    @Override
    public Mono<Void> onVerificationCompleted(AuditVerificationResult result) {
        return Mono.fromRunnable(() -> {
            status.recordResult(result);
            switch (result.status()) {
                case VALID -> log.info("Audit chain verification passed: {} entries verified",
                        result.verifiedEntries());
                case EMPTY -> log.info("Audit chain verification: no chained entries found");
                case BROKEN -> {
                    AuditVerificationResult.BrokenLink link = result.brokenLink();
                    // ERROR, not WARN: the tamper-evident log is the product's own evidence
                    // that nothing was altered. A break invalidates that claim.
                    log.error("AUDIT CHAIN INTEGRITY VIOLATION — the audit log has been altered. "
                                    + "Broken at entry {} (expected hash {}, actual {}). "
                                    + "{} of {} entries verified before the break. "
                                    + "Treat as a security incident: preserve the database, do not "
                                    + "truncate or re-chain audit_logs, and investigate who holds "
                                    + "write access to it.",
                            link == null ? "unknown" : link.entryId(),
                            link == null ? "unknown" : link.expectedHash(),
                            link == null ? "unknown" : link.actualHash(),
                            result.verifiedEntries(), result.totalEntries());
                }
            }
        });
    }

    @Override
    public Mono<Void> onVerificationFailed(Throwable error) {
        return Mono.fromRunnable(() -> {
            status.recordFailure(error);
            // An unverifiable chain is not a verified chain — do not downgrade this to a warning.
            log.error("Audit chain verification could not run — chain integrity is UNKNOWN "
                    + "until the next successful pass", error);
        });
    }
}
