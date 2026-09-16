package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.AuditIntegrityListener;
import org.openfilz.dms.service.AuditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.audit.chain.verification-enabled", havingValue = "true", matchIfMissing = true)
public class AuditVerificationScheduler {

    private final AuditService auditService;
    private final AuditIntegrityListener integrityListener;

    @Scheduled(cron = "${openfilz.audit.chain.verification-cron:0 0 3 * * ?}")
    public void verifyAuditChain() {
        log.info("Starting scheduled audit chain verification");
        auditService.verifyChain()
                // The listener decides what a verdict means (log, alert, notify); it owns the
                // severity so this scheduler never has to know about the alerting channel.
                .flatMap(result -> integrityListener.onVerificationCompleted(result)
                        .onErrorResume(alertError -> {
                            // Never let a failing alert channel swallow the verdict itself.
                            log.error("Audit integrity listener failed after verification", alertError);
                            return Mono.empty();
                        }))
                .onErrorResume(error -> integrityListener.onVerificationFailed(error)
                        .onErrorResume(alertError -> {
                            log.error("Audit integrity listener failed after a verification error",
                                    alertError);
                            return Mono.empty();
                        }))
                .subscribe();
    }
}
