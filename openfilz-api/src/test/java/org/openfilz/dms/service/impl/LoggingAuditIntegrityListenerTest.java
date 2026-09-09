package org.openfilz.dms.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.audit.AuditVerificationResult.AuditVerificationStatus;
import org.openfilz.dms.service.AuditIntegrityStatus;
import reactor.test.StepVerifier;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAuditIntegrityListenerTest {

    private AuditIntegrityStatus status;
    private LoggingAuditIntegrityListener listener;

    @BeforeEach
    void setUp() {
        status = new AuditIntegrityStatus();
        listener = new LoggingAuditIntegrityListener(status);
    }

    @Test
    void freshInstance_reportsNotVerified_andNotBroken() {
        assertThat(status.lastResult()).isEmpty();
        assertThat(status.lastFailureMessage()).isEmpty();
        // "never checked" must not read as "broken" — the API distinguishes the two.
        assertThat(status.isChainBroken()).isFalse();
    }

    @Test
    void validResult_isRecorded_andChainNotBroken() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.VALID, 10, 10, OffsetDateTime.now(), null);

        StepVerifier.create(listener.onVerificationCompleted(result)).verifyComplete();

        assertThat(status.lastResult()).contains(result);
        assertThat(status.isChainBroken()).isFalse();
    }

    @Test
    void brokenResult_flagsTheChainAsBroken() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.BROKEN, 10, 4, OffsetDateTime.now(),
                new AuditVerificationResult.BrokenLink(5L, "expected", "actual"));

        StepVerifier.create(listener.onVerificationCompleted(result)).verifyComplete();

        assertThat(status.isChainBroken()).isTrue();
        assertThat(status.lastResult()).contains(result);
    }

    @Test
    void brokenResultWithoutLinkDetail_doesNotThrow() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.BROKEN, 10, 4, OffsetDateTime.now(), null);

        StepVerifier.create(listener.onVerificationCompleted(result)).verifyComplete();

        assertThat(status.isChainBroken()).isTrue();
    }

    @Test
    void failure_isRecordedSeparatelyFromABrokenChain() {
        StepVerifier.create(listener.onVerificationFailed(new IllegalStateException("db down")))
                .verifyComplete();

        assertThat(status.lastFailureMessage()).contains("db down");
        assertThat(status.lastFailureAt()).isPresent();
        // A verification that could not run is unknown, not broken.
        assertThat(status.isChainBroken()).isFalse();
        assertThat(status.lastResult()).isEmpty();
    }

    @Test
    void successfulPass_clearsAPreviousFailure() {
        StepVerifier.create(listener.onVerificationFailed(new IllegalStateException("db down")))
                .verifyComplete();

        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.VALID, 3, 3, OffsetDateTime.now(), null);
        StepVerifier.create(listener.onVerificationCompleted(result)).verifyComplete();

        assertThat(status.lastFailureMessage()).isEmpty();
        assertThat(status.lastFailureAt()).isEmpty();
        assertThat(status.lastResult()).contains(result);
    }
}
