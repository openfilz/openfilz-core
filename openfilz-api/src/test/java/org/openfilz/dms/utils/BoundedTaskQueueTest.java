package org.openfilz.dms.utils;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class BoundedTaskQueueTest {

    private BoundedTaskQueue queue;

    @AfterEach
    void tearDown() {
        if (queue != null) {
            queue.dispose();
        }
    }

    @Test
    void neverRunsMoreThanConcurrencyTasksAtOnce() {
        queue = new BoundedTaskQueue("test", 3);
        AtomicInteger running = new AtomicInteger();
        AtomicInteger maxRunning = new AtomicInteger();
        AtomicInteger done = new AtomicInteger();

        IntStream.range(0, 20).forEach(i -> queue.run(Mono.defer(() -> {
                    maxRunning.accumulateAndGet(running.incrementAndGet(), Math::max);
                    return Mono.delay(Duration.ofMillis(20));
                })
                // doOnTerminate, not doFinally: doFinally runs after completion has propagated,
                // i.e. after the queue already started the next task.
                .doOnTerminate(() -> {
                    running.decrementAndGet();
                    done.incrementAndGet();
                })));

        await().atMost(Duration.ofSeconds(5)).until(() -> done.get() == 20);
        assertThat(maxRunning.get()).isEqualTo(3);
        await().atMost(Duration.ofSeconds(5)).until(() -> queue.pending() == 0);
    }

    @Test
    void startsTasksInSubmissionOrder() {
        queue = new BoundedTaskQueue("test", 1);
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());

        IntStream.range(0, 10).forEach(i -> queue.run(Mono.fromRunnable(() -> order.add(i))));

        await().atMost(Duration.ofSeconds(5)).until(() -> order.size() == 10);
        assertThat(order).containsExactly(0, 1, 2, 3, 4, 5, 6, 7, 8, 9);
    }

    @Test
    void queuedTaskIsNotSubscribedUntilASlotFrees() {
        queue = new BoundedTaskQueue("test", 1);
        Sinks.Empty<Void> blocker = Sinks.empty();
        AtomicInteger secondStarted = new AtomicInteger();

        queue.run(blocker.asMono());
        queue.run(Mono.fromRunnable(secondStarted::incrementAndGet));

        await().during(Duration.ofMillis(200)).atMost(Duration.ofSeconds(1)).until(() -> secondStarted.get() == 0);
        assertThat(queue.pending()).isEqualTo(2);

        blocker.tryEmitEmpty();
        await().atMost(Duration.ofSeconds(5)).until(() -> secondStarted.get() == 1);
    }

    @Test
    void submitMirrorsTheTaskOutcomeAndAFailureDoesNotStopTheQueue() {
        queue = new BoundedTaskQueue("test", 1);

        StepVerifier.create(queue.submit(Mono.error(new IllegalStateException("boom"))))
                .expectErrorMessage("boom")
                .verify(Duration.ofSeconds(5));
        StepVerifier.create(queue.submit(Mono.just("ok")))
                .expectNext("ok")
                .verifyComplete();
        StepVerifier.create(queue.submit(Mono.empty()))
                .verifyComplete();
    }
}
