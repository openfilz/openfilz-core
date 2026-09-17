package org.openfilz.dms.service;

import org.openfilz.dms.dto.audit.AuditVerificationResult;
import reactor.core.publisher.Mono;

/**
 * Seam notified when a scheduled audit-chain verification finishes.
 *
 * <p>A broken hash chain means the tamper-evident audit log has been altered — it is a
 * security incident, not a log line. The core ships {@link
 * org.openfilz.dms.service.impl.LoggingAuditIntegrityListener}, which records the outcome so
 * it is observable through the API; a deployment that has somewhere to raise an alarm
 * (notification, webhook, SIEM) supplies its own {@code @Primary} implementation instead.
 *
 * <p>Implementations MUST NOT throw: the scheduler treats an alert failure as non-fatal so a
 * broken chain is still recorded even when the alerting path is down.
 */
public interface AuditIntegrityListener {

    /**
     * Called after every scheduled verification, whatever its outcome.
     *
     * @param result the verification outcome; {@code status == BROKEN} carries the offending
     *               entry in {@link AuditVerificationResult#brokenLink()}
     */
    Mono<Void> onVerificationCompleted(AuditVerificationResult result);

    /**
     * Called when a scheduled verification could not run at all (database unreachable, …).
     * An unverifiable chain is not a valid chain: it must not pass silently.
     */
    Mono<Void> onVerificationFailed(Throwable error);
}
