package org.openfilz.dms.utils;

import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs asynchronous tasks at most {@code concurrency} at a time, in submission order; the rest
 * wait in an unbounded in-memory queue. Meant for fire-and-forget background work (text
 * extraction, thumbnails, embeddings…) that would otherwise all start at once when many
 * documents arrive together — an unzip or a bulk upload — and starve the request that created
 * them.
 * <p>
 * A task's failure never stops the queue. The {@link Mono} returned by {@link #submit} mirrors
 * the task's outcome; cancelling it does not cancel a task that is already queued or running.
 * Tasks must not wait for another task of the same queue, or they could deadlock it.
 */
@Slf4j
public class BoundedTaskQueue {

    private final String name;
    private final int concurrency;
    private final Sinks.Many<Mono<Void>> queue = Sinks.many().unicast().onBackpressureBuffer();
    private final AtomicInteger pending = new AtomicInteger();
    private final Disposable worker;

    public BoundedTaskQueue(String name, int concurrency) {
        this.name = name;
        this.concurrency = Math.max(1, concurrency);
        this.worker = queue.asFlux()
                .flatMap(task -> task
                        .onErrorResume(e -> {
                            // Callers log their own failures; this only keeps the queue alive.
                            log.debug("[{}] background task failed: {}", name, e.toString());
                            return Mono.empty();
                        })
                        .doFinally(_ -> pending.decrementAndGet()), this.concurrency, 1)
                .subscribe();
        log.info("[{}] bounded task queue started (concurrency={})", name, this.concurrency);
    }

    /** Queues {@code task}; it is subscribed once a slot is free. */
    public <T> Mono<T> submit(Mono<T> task) {
        return Mono.defer(() -> {
            Sinks.One<T> result = Sinks.one();
            Mono<Void> wrapped = task
                    .doOnSuccess(value -> {
                        if (value == null) {
                            result.tryEmitEmpty();
                        } else {
                            result.tryEmitValue(value);
                        }
                    })
                    .doOnError(result::tryEmitError)
                    .then();
            pending.incrementAndGet();
            // Several threads submit concurrently: busy-loop on the (brief) serialization conflict.
            queue.emitNext(wrapped, Sinks.EmitFailureHandler.busyLooping(Duration.ofSeconds(2)));
            return result.asMono();
        });
    }

    /** Queues {@code task} and forgets it (its failure is only logged). */
    public void run(Mono<?> task) {
        submit(task).subscribe(null, _ -> { });
    }

    /** Tasks queued or running. */
    public int pending() {
        return pending.get();
    }

    public int concurrency() {
        return concurrency;
    }

    public void dispose() {
        queue.tryEmitComplete();
        worker.dispose();
        log.debug("[{}] bounded task queue stopped", name);
    }
}
