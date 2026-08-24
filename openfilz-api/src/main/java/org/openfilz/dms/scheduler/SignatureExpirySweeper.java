package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.SignatureProperties;
import org.openfilz.dms.service.SignatureService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Flips SENT → EXPIRED for e-Sign envelopes past their deadline. Defensive: the public
 * endpoints also reject expired envelopes, but the sweeper makes the status column converge
 * (listings, metrics, audit, webhooks). Self-guards on {@code openfilz.signature.active} at
 * runtime — never a bean condition (native image).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class SignatureExpirySweeper {

    private final SignatureService service;
    private final SignatureProperties props;

    @Scheduled(cron = "${openfilz.signature.sweep.cron:0 */5 * * * ?}")
    public void sweep() {
        if (!props.isActive()) return;
        service.sweepExpired()
                .doOnNext(n -> {
                    if (n > 0) log.info("[e-sign] sweeper expired {} envelope(s)", n);
                })
                .doOnError(err -> log.error("[e-sign] expiry sweeper failed", err))
                .subscribe();
    }
}
