package org.openfilz.dms.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.service.onlyoffice.EditorSessionStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Removes the OnlyOffice editor sessions nothing can come back to. A closed session is kept a day,
 * so that a callback the document server retries still finds it. A running session goes after a
 * week of silence: a save arriving later finds no row and is saved with a warning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EditorSessionCleanupScheduler {

    private final EditorSessionStore store;

    @Scheduled(cron = "${onlyoffice.sessions.cleanup-cron:0 30 3 * * ?}")
    public void deleteStaleSessions() {
        OffsetDateTime now = OffsetDateTime.now();
        store.deleteStale(now.minusDays(1), now.minusDays(7))
                .doOnNext(deleted -> {
                    if (deleted > 0) {
                        log.info("Removed {} stale OnlyOffice editor session(s)", deleted);
                    }
                })
                .doOnError(e -> log.warn("Could not remove stale OnlyOffice editor sessions: {}", e.getMessage()))
                .onErrorComplete()
                .subscribe();
    }
}
