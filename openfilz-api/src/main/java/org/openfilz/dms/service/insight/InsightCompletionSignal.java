package org.openfilz.dms.service.insight;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process wake-up for whoever waits on a document's insight row: the insight worker completes
 * the signal at every terminal write (DONE, FAILED, SKIPPED) and the waiter (smart filing, which
 * needs the tier-2 row before asking the model) wakes at once instead of polling the row.
 *
 * <p>Register <em>before</em> the first read of the row: a completion that lands between the read
 * and the registration is then still caught. A signal is a hint, not a contract: the waiter keeps a
 * slow fallback read for a row finished by another node (both queues run on the node that received
 * the upload, so the signal is the normal path).
 */
@Component
public class InsightCompletionSignal {

    private final Map<UUID, CompletableFuture<Void>> waiters = new ConcurrentHashMap<>();

    /** The future to wait on; every waiter of the same document shares it. */
    public CompletableFuture<Void> register(UUID documentId) {
        return waiters.computeIfAbsent(documentId, id -> new CompletableFuture<>());
    }

    /** The row reached a terminal state: wake the waiters, if any. */
    public void complete(UUID documentId) {
        CompletableFuture<Void> waiter = waiters.remove(documentId);
        if (waiter != null) {
            waiter.complete(null);
        }
    }

    /** A waiter gave up (deadline, interrupt): drop its registration, and only its own. */
    public void forget(UUID documentId, CompletableFuture<Void> registered) {
        waiters.remove(documentId, registered);
    }

    /** Registrations still open; for tests, to prove waiters do not leak. */
    public int pending() {
        return waiters.size();
    }
}
