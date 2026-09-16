package org.openfilz.dms.service;

import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Last known outcome of the scheduled audit-chain verification.
 *
 * <p>Exists so operators can read the chain's state without paying for a full re-scan on every
 * poll: {@code GET /api/v1/audit/verify} walks the whole table, this is the cached verdict of
 * the last nightly pass.
 *
 * <p><strong>In-memory and per-instance on purpose.</strong> It is a monitoring cache, never a
 * source of truth: it is empty until the first scheduled pass completes, it resets on restart,
 * and two instances of the API can disagree. The authoritative answer is always a fresh
 * {@link AuditService#verifyChain()}. Persisting the verdict would put a claim about the audit
 * log's integrity inside the very database the verification is meant to police.
 */
@Component
public class AuditIntegrityStatus {

    private volatile AuditVerificationResult lastResult;
    private volatile OffsetDateTime lastFailureAt;
    private volatile String lastFailureMessage;

    public void recordResult(AuditVerificationResult result) {
        this.lastResult = result;
        this.lastFailureAt = null;
        this.lastFailureMessage = null;
    }

    public void recordFailure(Throwable error) {
        this.lastFailureAt = OffsetDateTime.now();
        this.lastFailureMessage = error == null ? "unknown error" : String.valueOf(error.getMessage());
    }

    /** Empty until the first scheduled verification has completed on this instance. */
    public Optional<AuditVerificationResult> lastResult() {
        return Optional.ofNullable(lastResult);
    }

    /** Set when the last scheduled verification could not run; cleared by the next success. */
    public Optional<String> lastFailureMessage() {
        return Optional.ofNullable(lastFailureMessage);
    }

    public Optional<OffsetDateTime> lastFailureAt() {
        return Optional.ofNullable(lastFailureAt);
    }

    /**
     * {@code true} only when the last scheduled pass ran AND reported a broken chain. A pass
     * that never ran, or that failed to run, is <em>not</em> reported as broken here — read
     * {@link #lastFailureMessage()} for that case rather than treating "not broken" as "fine".
     */
    public boolean isChainBroken() {
        AuditVerificationResult result = this.lastResult;
        return result != null
                && result.status() == AuditVerificationResult.AuditVerificationStatus.BROKEN;
    }
}
