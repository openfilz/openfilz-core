package org.openfilz.dms.utils;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * One pass over stored secrets after a key rotation: each value not under the current key is decrypted
 * with whichever key opens it and written again under the current one, through a conditional update that
 * only applies when the row still holds the value read. Rows are expected to be few (one per user or per
 * mailbox): they are handled one at a time, with no job table.
 */
public final class SecretReencryptionPass {

    private SecretReencryptionPass() {
    }

    /** A stored secret: the row's key and its current column value. */
    public record Row(Object id, String value) {
    }

    /** Writes {@code newValue} where the row still holds {@code oldValue}; emits the rows updated (0 or 1). */
    @FunctionalInterface
    public interface ConditionalUpdate {
        Mono<Long> apply(Object id, String oldValue, String newValue);
    }

    /**
     * @param rewritten        values written again under the current key
     * @param current          values already under it
     * @param unreadable       values no configured key opens, left as they are
     * @param changedMeanwhile values changed by someone else (a save, another instance) between the read and the update
     */
    public record Counts(int rewritten, int current, int unreadable, int changedMeanwhile) {

        public static final Counts NONE = new Counts(0, 0, 0, 0);

        Counts plus(Counts other) {
            return new Counts(rewritten + other.rewritten, current + other.current,
                    unreadable + other.unreadable, changedMeanwhile + other.changedMeanwhile);
        }

        @Override
        public String toString() {
            return rewritten + " rewritten, " + current + " already current, " + unreadable + " unreadable (left as they are), "
                    + changedMeanwhile + " changed meanwhile";
        }
    }

    /**
     * @param rows         the stored values
     * @param rewrite      the value under the current key, or null when it already is; throws when no key opens it
     * @param isUnreadable which exceptions of {@code rewrite} mean "no key opens it" (others fail the pass)
     * @param update       the conditional update
     */
    public static Mono<Counts> run(Flux<Row> rows, UnaryOperator<String> rewrite,
                                   Predicate<RuntimeException> isUnreadable, ConditionalUpdate update) {
        return rows.concatMap(row -> {
                    String next;
                    try {
                        next = rewrite.apply(row.value());
                    } catch (RuntimeException e) {
                        if (isUnreadable.test(e)) {
                            return Mono.just(new Counts(0, 0, 1, 0));
                        }
                        return Mono.error(e);
                    }
                    if (next == null) {
                        return Mono.just(new Counts(0, 1, 0, 0));
                    }
                    return update.apply(row.id(), row.value(), next)
                            .defaultIfEmpty(0L)
                            .map(updated -> updated > 0 ? new Counts(1, 0, 0, 0) : new Counts(0, 0, 0, 1));
                })
                .reduce(Counts.NONE, Counts::plus);
    }
}
