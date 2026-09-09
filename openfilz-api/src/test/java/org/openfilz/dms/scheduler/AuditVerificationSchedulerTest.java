package org.openfilz.dms.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.audit.AuditVerificationResult.AuditVerificationStatus;
import org.openfilz.dms.service.AuditIntegrityListener;
import org.openfilz.dms.service.AuditService;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditVerificationSchedulerTest {

    @Mock
    private AuditService auditService;

    @Mock
    private AuditIntegrityListener integrityListener;

    private AuditVerificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuditVerificationScheduler(auditService, integrityListener);
    }

    @Test
    void verifyAuditChain_validResult_notifiesListener() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.VALID, 10, 10, OffsetDateTime.now(), null);
        when(auditService.verifyChain()).thenReturn(Mono.just(result));
        when(integrityListener.onVerificationCompleted(any())).thenReturn(Mono.empty());

        scheduler.verifyAuditChain();

        verify(auditService).verifyChain();
        verify(integrityListener).onVerificationCompleted(result);
        verify(integrityListener, never()).onVerificationFailed(any());
    }

    @Test
    void verifyAuditChain_brokenResult_handsTheBreakToTheListener() {
        AuditVerificationResult.BrokenLink link =
                new AuditVerificationResult.BrokenLink(5L, "expected", "actual");
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.BROKEN, 10, 4, OffsetDateTime.now(), link);
        when(auditService.verifyChain()).thenReturn(Mono.just(result));
        when(integrityListener.onVerificationCompleted(any())).thenReturn(Mono.empty());

        scheduler.verifyAuditChain();

        ArgumentCaptor<AuditVerificationResult> captor =
                ArgumentCaptor.forClass(AuditVerificationResult.class);
        verify(integrityListener).onVerificationCompleted(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(AuditVerificationStatus.BROKEN);
        assertThat(captor.getValue().brokenLink()).isEqualTo(link);
    }

    @Test
    void verifyAuditChain_emptyResult_notifiesListener() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.EMPTY, 0, 0, OffsetDateTime.now(), null);
        when(auditService.verifyChain()).thenReturn(Mono.just(result));
        when(integrityListener.onVerificationCompleted(any())).thenReturn(Mono.empty());

        scheduler.verifyAuditChain();

        verify(integrityListener).onVerificationCompleted(result);
    }

    @Test
    void verifyAuditChain_error_isReportedAsAFailedVerification() {
        RuntimeException boom = new RuntimeException("boom");
        when(auditService.verifyChain()).thenReturn(Mono.error(boom));
        when(integrityListener.onVerificationFailed(any())).thenReturn(Mono.empty());

        scheduler.verifyAuditChain();

        verify(integrityListener).onVerificationFailed(boom);
        verify(integrityListener, never()).onVerificationCompleted(any());
    }

    /**
     * A broken alerting channel must not hide a broken chain: the scheduler swallows the
     * listener's own failure rather than letting it bubble out of the scheduled method.
     */
    @Test
    void verifyAuditChain_listenerFailure_doesNotPropagate() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.BROKEN, 10, 4, OffsetDateTime.now(),
                new AuditVerificationResult.BrokenLink(5L, "expected", "actual"));
        when(auditService.verifyChain()).thenReturn(Mono.just(result));
        when(integrityListener.onVerificationCompleted(any()))
                .thenReturn(Mono.error(new IllegalStateException("alerting down")));

        scheduler.verifyAuditChain();

        verify(integrityListener).onVerificationCompleted(result);
    }
}
