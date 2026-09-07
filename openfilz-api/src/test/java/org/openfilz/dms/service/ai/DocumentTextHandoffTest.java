package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The upload → filing text hand-off. What matters is that it stays small: it must hold nothing
 * when filing is off, nothing after the filing took its entry, and never more characters than
 * the ceiling however many uploads are in flight.
 */
class DocumentTextHandoffTest {

    @Test
    void takeReturnsTheTextOnceAndThenNothing() {
        DocumentTextHandoff handoff = handoff(properties(true, 1_000_000, Duration.ofMinutes(10)));
        UUID id = UUID.randomUUID();

        handoff.offer(id, "the invoice text");

        assertThat(handoff.take(id)).contains("the invoice text");
        assertThat(handoff.take(id)).isEmpty();
        assertThat(handoff.size()).isZero();
    }

    @Test
    void nothingIsHeldWhenSmartFilingIsOff() {
        AiProperties properties = properties(true, 1_000_000, Duration.ofMinutes(10));
        properties.getAutoFile().setActive(false);
        DocumentTextHandoff handoff = handoff(properties);
        UUID id = UUID.randomUUID();

        handoff.offer(id, "nobody will ever file this");

        assertThat(handoff.take(id)).isEmpty();
        assertThat(handoff.size()).isZero();
    }

    @Test
    void nothingIsHeldWhenTheHandoffIsDisabled() {
        DocumentTextHandoff handoff = handoff(properties(false, 1_000_000, Duration.ofMinutes(10)));
        UUID id = UUID.randomUUID();

        handoff.offer(id, "the filing will re-parse instead");

        assertThat(handoff.size()).isZero();
    }

    @Test
    void blankAndNullOffersAreIgnored() {
        DocumentTextHandoff handoff = handoff(properties(true, 1_000_000, Duration.ofMinutes(10)));
        UUID id = UUID.randomUUID();

        handoff.offer(id, null);
        handoff.offer(id, "   ");
        handoff.offer(null, "orphan");

        assertThat(handoff.size()).isZero();
        assertThat(handoff.take(id)).isEmpty();
    }

    @Test
    void aDeletedDocumentLeavesNothingBehind() {
        DocumentTextHandoff handoff = handoff(properties(true, 1_000_000, Duration.ofMinutes(10)));
        UUID id = UUID.randomUUID();

        handoff.offer(id, "gone in a moment");
        handoff.forget(id);

        assertThat(handoff.size()).isZero();
    }

    /**
     * The bound that matters: a thousand uploads nobody files must not hold a thousand texts.
     * The ceiling is counted in characters, so the eviction is driven by the size of what is
     * held and not by an entry count.
     */
    @Test
    void theTotalNeverExceedsTheCharacterCeiling() {
        int perEntry = 1_000;
        int ceiling = 20_000;
        DocumentTextHandoff handoff = handoff(properties(true, ceiling, Duration.ofMinutes(10)));
        String text = "x".repeat(perEntry);

        for (int i = 0; i < 1_000; i++) {
            handoff.offer(UUID.randomUUID(), text);
        }

        // Caffeine evicts asynchronously; cleanUp() (inside size()) drains the pending work first.
        assertThat(handoff.size() * perEntry).isLessThanOrEqualTo((long) ceiling);
    }

    @Test
    void anEntryNobodyTookExpires() {
        DocumentTextHandoff handoff = handoff(properties(true, 1_000_000, Duration.ofNanos(1)));
        UUID id = UUID.randomUUID();

        handoff.offer(id, "never filed");

        assertThat(handoff.take(id)).isEmpty();
    }

    private static DocumentTextHandoff handoff(AiProperties properties) {
        return new DocumentTextHandoff(properties);
    }

    private static AiProperties properties(boolean handoffEnabled, long maxCharacters, Duration ttl) {
        AiProperties properties = new AiProperties();
        properties.setActive(true);
        properties.getAutoFile().setActive(true);
        properties.getAutoFile().getTextHandoff().setEnabled(handoffEnabled);
        properties.getAutoFile().getTextHandoff().setMaxCharacters(maxCharacters);
        properties.getAutoFile().getTextHandoff().setTtl(ttl);
        return properties;
    }
}
