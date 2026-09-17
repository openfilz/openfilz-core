package org.openfilz.dms.service.impl;

import io.minio.messages.Retention;
import io.minio.messages.RetentionMode;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.MinioProperties;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Period;
import java.time.ZonedDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D1 point 4 — object-lock semantics under WORM.
 * <p>
 * The storage layer used to set {@code legalHold(wormMode)} on every PUT and never lift it. A legal
 * hold has no expiry by design, so a WORM deployment could never dispose of anything at the end of
 * its retention period — the one operation a records archive must eventually perform. Retention in
 * {@code COMPLIANCE} mode with a {@code retain-until-date} says the same thing for a bounded time,
 * and the legal hold goes back to being what it is: an explicit evidentiary freeze, set per
 * document when litigation demands it, not a side effect of the mode.
 * <p>
 * The start-up guard matters as much as the lock: dropping the legal hold without configuring a
 * retention period would leave WORM objects deletable at the storage layer, with only the API
 * refusing writes. That is a silent loss of defence in depth, so it fails the boot instead.
 */
class MinioWormRetentionTest {

    private static MinioStorageService service(boolean wormMode, Period retention) {
        MinioStorageService service = new MinioStorageService(new MinioProperties());
        ReflectionTestUtils.setField(service, "wormMode", wormMode);
        ReflectionTestUtils.setField(service, "wormRetention",
                retention == null ? Optional.empty() : Optional.of(retention));
        return service;
    }

    @Test
    void wormWritesCarryComplianceRetention() {
        MinioStorageService service = service(true, Period.ofYears(10));

        Retention retention = service.objectLock();

        assertEquals(RetentionMode.COMPLIANCE, retention.mode(),
                "GOVERNANCE can be bypassed by a privileged user — an archive needs COMPLIANCE");
        ZonedDateTime expected = ZonedDateTime.now().plusYears(10);
        assertTrue(retention.retainUntilDate().isAfter(expected.minusDays(1))
                        && retention.retainUntilDate().isBefore(expected.plusDays(1)),
                "retain-until-date should be now + the configured period, was " + retention.retainUntilDate());
    }

    @Test
    void withoutWormNoLockIsApplied() {
        assertNull(service(false, Period.ofYears(10)).objectLock(),
                "an ordinary deployment must keep writing ordinary objects");
        assertNull(service(false, null).objectLock());
    }

    @Test
    void wormWithoutRetentionRefusesToStart() {
        // Guard must run before the MinioClient is built, so this never touches the network.
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service(true, null).init());
        assertTrue(error.getMessage().contains("worm-retention"),
                "the message must name the property to set, was: " + error.getMessage());
    }

    @Test
    void aZeroOrNegativeRetentionIsRefused() {
        assertThrows(IllegalStateException.class, () -> service(true, Period.ZERO).init());
        assertThrows(IllegalStateException.class, () -> service(true, Period.ofYears(-1)).init());
    }
}
