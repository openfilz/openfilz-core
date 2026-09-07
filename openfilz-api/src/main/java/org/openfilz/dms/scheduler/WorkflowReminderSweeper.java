package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.WorkflowProperties;
import org.openfilz.dms.service.workflow.WorkflowService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Reminds the candidates of every overdue workflow task once. Self-guards on
 * {@code openfilz.workflows.active} at runtime — never a bean condition (native image).
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class WorkflowReminderSweeper {

    private final WorkflowService service;
    private final WorkflowProperties props;

    @Scheduled(cron = "${openfilz.workflows.sweep.cron:0 0 * * * ?}")
    public void sweep() {
        if (!props.isActive()) return;
        service.remindOverdue()
                .doOnNext(n -> {
                    if (n > 0) log.info("[workflows] sweeper reminded {} overdue task(s)", n);
                })
                .doOnError(err -> log.error("[workflows] reminder sweeper failed", err))
                .subscribe();
    }
}
