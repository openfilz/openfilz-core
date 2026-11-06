package org.openfilz.dms.security.session;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RevokedSessionStoreTest {

    private static final String SID = "session-1";

    /** The store with its table replaced by what the test answers. */
    private static class Store extends RevokedSessionStore {

        final AtomicInteger queries = new AtomicInteger();
        final List<String> saved = new ArrayList<>();
        Mono<Optional<Instant>> answer = Mono.just(Optional.empty());

        Store(Duration lookupCacheTtl) {
            super(null, Duration.ofHours(12), lookupCacheTtl);
        }

        @Override
        Mono<Optional<Instant>> findExpiry(String sid) {
            return Mono.defer(() -> {
                queries.incrementAndGet();
                return answer;
            });
        }

        @Override
        Mono<Void> save(String sid, Instant expiresAt) {
            return Mono.fromRunnable(() -> saved.add(sid));
        }
    }

    @Test
    void theRequestsOfOneSessionArrivingTogetherShareOneQuery() {
        Store store = new Store(Duration.ofSeconds(15));
        Sinks.One<Optional<Instant>> table = Sinks.one();
        store.answer = table.asMono();

        List<CompletableFuture<Boolean>> page = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            page.add(store.isRevoked(SID).toFuture());
        }
        table.tryEmitValue(Optional.empty());

        page.forEach(request -> assertFalse(request.join()));
        assertEquals(1, store.queries.get());
        // ...and the answer is kept for the requests that follow.
        assertFalse(store.isRevoked(SID).block());
        assertEquals(1, store.queries.get());
    }

    @Test
    void aRequestGivingUpDoesNotCancelTheQueryTheOthersWaitOn() {
        Store store = new Store(Duration.ofSeconds(15));
        Sinks.One<Optional<Instant>> table = Sinks.one();
        store.answer = table.asMono();

        store.isRevoked(SID).subscribe().dispose();
        CompletableFuture<Boolean> other = store.isRevoked(SID).toFuture();
        table.tryEmitValue(Optional.of(Instant.now().plusSeconds(60)));

        assertTrue(other.join());
        assertEquals(1, store.queries.get());
    }

    @Test
    void aSessionSignedOutHereIsRefusedAtOnceWhateverWasCached() {
        Store store = new Store(Duration.ofSeconds(15));
        assertFalse(store.isRevoked(SID).block());

        store.revoke(SID).block();

        assertTrue(store.isRevoked(SID).block());
        assertEquals(List.of(SID), store.saved);
        assertEquals(1, store.queries.get());
    }

    @Test
    void aSessionSignedOutThroughAnotherReplicaIsLearnedFromTheTable() {
        Store store = new Store(Duration.ZERO); // nothing trusted: every call asks the table
        assertFalse(store.isRevoked(SID).block());

        store.answer = Mono.just(Optional.of(Instant.now().plusSeconds(60)));
        assertTrue(store.isRevoked(SID).block());

        // Known from then on, without asking again.
        int queries = store.queries.get();
        assertTrue(store.isRevoked(SID).block());
        assertEquals(queries, store.queries.get());
    }

    @Test
    void anUnreadableTableAnswersNotRevokedAndIsAskedAgain() {
        Store store = new Store(Duration.ofSeconds(15));
        store.answer = Mono.error(new IllegalStateException("database down"));
        StepVerifier.create(store.isRevoked(SID)).expectNext(false).verifyComplete();

        // The failure was not kept as an answer: the next request finds the revocation.
        store.answer = Mono.just(Optional.of(Instant.now().plusSeconds(60)));
        StepVerifier.create(store.isRevoked(SID)).expectNext(true).verifyComplete();
        assertEquals(2, store.queries.get());
    }

    @Test
    void aRevocationIsForgottenOnceItsTimeHasPassed() {
        Store store = new Store(Duration.ZERO);
        store.answer = Mono.just(Optional.of(Instant.now().minusSeconds(1)));
        assertTrue(store.isRevoked(SID).block(), "the table said revoked");

        store.answer = Mono.just(Optional.empty()); // the row is gone
        assertFalse(store.isRevoked(SID).block());
    }
}
