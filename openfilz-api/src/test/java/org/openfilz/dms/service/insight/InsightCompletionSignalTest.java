package org.openfilz.dms.service.insight;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class InsightCompletionSignalTest {

    private final InsightCompletionSignal signal = new InsightCompletionSignal();

    @Test
    void completeWakesTheWaiter() throws Exception {
        UUID id = UUID.randomUUID();
        CompletableFuture<Void> waiter = signal.register(id);
        assertThat(waiter).isNotDone();

        signal.complete(id);

        waiter.get(1, TimeUnit.SECONDS);
        assertThat(waiter).isDone();
        assertThat(signal.pending()).as("a completed registration is dropped").isZero();
    }

    @Test
    void completeWithoutWaiterIsANoOp() {
        signal.complete(UUID.randomUUID());
        assertThat(signal.pending()).isZero();
    }

    @Test
    void waitersOfOneDocumentShareTheFuture() {
        UUID id = UUID.randomUUID();
        assertThat(signal.register(id)).isSameAs(signal.register(id));
        assertThat(signal.pending()).isEqualTo(1);
    }

    @Test
    void forgetDropsOnlyItsOwnRegistration() {
        UUID id = UUID.randomUUID();
        CompletableFuture<Void> first = signal.register(id);
        signal.complete(id);
        CompletableFuture<Void> second = signal.register(id);

        signal.forget(id, first);
        assertThat(signal.pending()).as("the stale future does not evict the live one").isEqualTo(1);

        signal.forget(id, second);
        assertThat(signal.pending()).isZero();
        assertThat(second).isNotDone();
    }

    @Test
    void completeBeforeRegisterLeavesNothingBehind() {
        // The waiter closes this window by registering before its first read of the row: a
        // completion that arrives earlier is simply not a signal, and the read sees the row.
        UUID id = UUID.randomUUID();
        signal.complete(id);
        CompletableFuture<Void> waiter = signal.register(id);
        assertThat(waiter).isNotDone();
        signal.forget(id, waiter);
        assertThat(signal.pending()).isZero();
    }
}
