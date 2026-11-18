package org.openfilz.dms.utils;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecretReencryptionPassTest {

    /** An in-memory table with a conditional update, as two instances would share it. */
    static final class Table {
        final Map<Object, String> rows = new LinkedHashMap<>();
        final List<String> updates = new ArrayList<>();

        Flux<SecretReencryptionPass.Row> select() {
            return Flux.defer(() -> Flux.fromIterable(new ArrayList<>(rows.entrySet()))
                    .map(e -> new SecretReencryptionPass.Row(e.getKey(), e.getValue())));
        }

        Mono<Long> update(Object id, String previous, String next) {
            return Mono.fromSupplier(() -> {
                synchronized (rows) {
                    if (previous.equals(rows.get(id))) {
                        rows.put(id, next);
                        updates.add(id + "=" + next);
                        return 1L;
                    }
                    return 0L;
                }
            });
        }
    }

    @Test
    void countsRewrittenCurrentAndUnreadable_andLeavesUnreadableRows() throws Exception {
        String oldKey = SecretKeyringTest.randomKey();
        String newKey = SecretKeyringTest.randomKey();
        SecretKeyring before = SecretKeyring.of(oldKey, null, null, "K", "P");
        SecretKeyring after = SecretKeyring.of(newKey, oldKey, null, "K", "P");
        String lost = SecretKeyring.of(SecretKeyringTest.randomKey(), null, null, "K", "P").encrypt("lost");
        Table table = new Table();
        table.rows.put("a", before.encrypt("a-secret"));
        table.rows.put("b", SecretKeyringTest.legacy(oldKey, "b-secret", null));
        table.rows.put("c", after.encrypt("c-secret"));
        table.rows.put("d", lost);

        SecretReencryptionPass.Counts counts = SecretReencryptionPass.run(table.select(), after::reencrypt,
                SecretKeyring.UnreadableSecretException.class::isInstance, table::update).block();

        assertEquals(new SecretReencryptionPass.Counts(2, 1, 1, 0), counts);
        assertTrue(after.isCurrent(table.rows.get("a")));
        assertTrue(after.isCurrent(table.rows.get("b")));
        assertEquals("a-secret", after.decrypt(table.rows.get("a")));
        assertEquals("b-secret", after.decrypt(table.rows.get("b")));
        assertEquals(lost, table.rows.get("d"));
    }

    @Test
    void secondInstanceChangesNothing() {
        String oldKey = SecretKeyringTest.randomKey();
        String newKey = SecretKeyringTest.randomKey();
        SecretKeyring after = SecretKeyring.of(newKey, oldKey, null, "K", "P");
        Table table = new Table();
        table.rows.put("a", SecretKeyring.of(oldKey, null, null, "K", "P").encrypt("a-secret"));

        SecretReencryptionPass.run(table.select(), after::reencrypt,
                SecretKeyring.UnreadableSecretException.class::isInstance, table::update).block();
        String once = table.rows.get("a");
        SecretReencryptionPass.Counts second = SecretReencryptionPass.run(table.select(), after::reencrypt,
                SecretKeyring.UnreadableSecretException.class::isInstance, table::update).block();

        assertEquals(new SecretReencryptionPass.Counts(0, 1, 0, 0), second);
        assertEquals(once, table.rows.get("a"));
        assertEquals(1, table.updates.size());
    }

    @Test
    void rowChangedBetweenReadAndUpdate_isNotOverwritten() {
        String oldKey = SecretKeyringTest.randomKey();
        SecretKeyring after = SecretKeyring.of(SecretKeyringTest.randomKey(), oldKey, null, "K", "P");
        Table table = new Table();
        String stale = SecretKeyring.of(oldKey, null, null, "K", "P").encrypt("old");
        table.rows.put("a", stale);
        // Another instance (or a save) writes the row after this pass read it
        String savedMeanwhile = after.encrypt("new");
        Flux<SecretReencryptionPass.Row> readBeforeTheSave = Flux.just(new SecretReencryptionPass.Row("a", stale))
                .doOnComplete(() -> table.rows.put("a", savedMeanwhile));

        SecretReencryptionPass.Counts counts = SecretReencryptionPass.run(readBeforeTheSave.collectList().flatMapMany(Flux::fromIterable),
                after::reencrypt, SecretKeyring.UnreadableSecretException.class::isInstance, table::update).block();

        assertEquals(new SecretReencryptionPass.Counts(0, 0, 0, 1), counts);
        assertEquals(savedMeanwhile, table.rows.get("a"));
    }

    @Test
    void anotherError_failsThePass() {
        Table table = new Table();
        table.rows.put("a", "x");

        assertThrows(IllegalStateException.class, () -> SecretReencryptionPass.run(table.select(), v -> {
            throw new IllegalStateException("boom");
        }, SecretKeyring.UnreadableSecretException.class::isInstance, table::update).block());
    }
}
