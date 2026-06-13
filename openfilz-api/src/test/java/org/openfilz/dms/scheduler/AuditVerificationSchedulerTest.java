package org.openfilz.dms.scheduler;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openfilz.dms.dto.audit.AuditVerificationResult;
import org.openfilz.dms.dto.audit.AuditVerificationResult.AuditVerificationStatus;
import org.openfilz.dms.service.AuditService;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditVerificationSchedulerTest {

    @Mock
    private AuditService auditService;

    private AuditVerificationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new AuditVerificationScheduler(auditService);
    }

    @Test
    void verifyAuditChain_validResult_logsAndCompletes() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.VALID, 10, 10, OffsetDateTime.now(), null);
        when(auditService.verifyChain()).thenReturn(Mono.just(result));

        scheduler.verifyAuditChain();

        verify(auditService).verifyChain();
    }

    @Test
    void verifyAuditChain_brokenResult_logsViolation() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.BROKEN, 10, 4, OffsetDateTime.now(),
                new AuditVerificationResult.BrokenLink(5L, "expected", "actual"));
        when(auditService.verifyChain()).thenReturn(Mono.just(result));

        scheduler.verifyAuditChain();

        verify(auditService).verifyChain();
    }

    @Test
    void verifyAuditChain_emptyResult_logsNoEntries() {
        AuditVerificationResult result = new AuditVerificationResult(
                AuditVerificationStatus.EMPTY, 0, 0, OffsetDateTime.now(), null);
        when(auditService.verifyChain()).thenReturn(Mono.just(result));

        scheduler.verifyAuditChain();

        verify(auditService).verifyChain();
    }

    @Test
    void verifyAuditChain_error_isHandled() {
        when(auditService.verifyChain()).thenReturn(Mono.error(new RuntimeException("boom")));

        scheduler.verifyAuditChain();

        verify(auditService).verifyChain();
    }
}
