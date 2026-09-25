package org.openfilz.dms.service.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.response.quota.QuotaSource;
import org.openfilz.dms.dto.response.quota.QuotaUsage;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.InstanceQuotaExceededException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Resolution and enforcement rules of {@link DefaultStorageQuotaService}, with the storage-facing
 * seams replaced by in-memory maps (no database). No security context → the caller is
 * {@code anonymousUser}.
 */
class DefaultStorageQuotaServiceTest {

    private static final long MB = 1024L * 1024L;
    private static final String ME = "anonymousUser";

    private final QuotaProperties props = new QuotaProperties();
    private final Map<String, Long> used = new HashMap<>();
    private final Map<String, Long> overrides = new HashMap<>();
    private final Map<String, DefaultStorageQuotaService.InheritedQuota> inherited = new HashMap<>();
    private long instanceUsed;

    private DefaultStorageQuotaService service;

    @BeforeEach
    void setUp() {
        service = new DefaultStorageQuotaService(props, null, null) {
            @Override
            protected Mono<Long> usedBytes(String username) {
                return Mono.just(used.getOrDefault(username, 0L));
            }

            @Override
            protected Mono<Optional<Long>> override(String username) {
                return Mono.just(Optional.ofNullable(overrides.get(username)));
            }

            @Override
            protected Mono<Map<String, Long>> overrides() {
                return Mono.just(Map.copyOf(overrides));
            }

            @Override
            protected Mono<Map<String, InheritedQuota>> inheritedQuotas(Collection<String> usernames) {
                Map<String, InheritedQuota> out = new HashMap<>();
                usernames.forEach(u -> { if (inherited.containsKey(u)) out.put(u, inherited.get(u)); });
                return Mono.just(out);
            }

            @Override
            protected Flux<UserStorageRow> chargedUsers() {
                return Flux.fromIterable(used.entrySet()).map(e -> new UserStorageRow(e.getKey(), null, e.getValue()));
            }

            @Override
            public Mono<Long> instanceUsedBytes() {
                return Mono.just(instanceUsed);
            }
        };
    }

    @Test
    void fileSize_zeroMeansNoLimit() {
        props.setFileUpload(0);
        StepVerifier.create(service.checkFileSize("a.bin", 10_000 * MB)).verifyComplete();
        assertNull(service.maxFileSizeBytes());
    }

    @Test
    void fileSize_overLimit_413() {
        props.setFileUpload(1);
        StepVerifier.create(service.checkFileSize("a.bin", 2 * MB)).expectError(FileSizeExceededException.class).verify();
    }

    @Test
    void defaultLimit_applies_whenNothingMoreSpecific() {
        props.setUser(10);
        used.put(ME, 9 * MB);
        StepVerifier.create(service.checkStorage(2 * MB)).expectError(UserQuotaExceededException.class).verify();
        StepVerifier.create(service.checkStorage(MB)).verifyComplete();
    }

    @Test
    void userOverride_winsOverGroupAndDefault() {
        props.setUser(1);
        inherited.put(ME, new DefaultStorageQuotaService.InheritedQuota(2, "Sales"));
        overrides.put(ME, 100L);
        used.put(ME, 50 * MB);
        StepVerifier.create(service.checkStorage(10 * MB)).verifyComplete();
        StepVerifier.create(service.myQuota())
                .assertNext(q -> {
                    assertEquals(QuotaSource.USER, q.source());
                    assertEquals(100 * MB, q.limitBytes());
                    assertEquals(50 * MB, q.usedBytes());
                })
                .verifyComplete();
    }

    @Test
    void userOverrideZero_exemptsTheUser() {
        props.setUser(1);
        overrides.put(ME, 0L);
        used.put(ME, 500 * MB);
        StepVerifier.create(service.checkStorage(500 * MB)).verifyComplete();
        StepVerifier.create(service.myQuota()).assertNext(q -> assertNull(q.limitBytes())).verifyComplete();
    }

    @Test
    void groupLimit_winsOverDefault() {
        props.setUser(1);
        inherited.put(ME, new DefaultStorageQuotaService.InheritedQuota(20, "Sales"));
        used.put(ME, 5 * MB);
        StepVerifier.create(service.checkStorage(10 * MB)).verifyComplete();
        StepVerifier.create(service.checkStorage(16 * MB)).expectError(UserQuotaExceededException.class).verify();
        StepVerifier.create(service.myQuota())
                .assertNext(q -> {
                    assertEquals(QuotaSource.GROUP, q.source());
                    assertEquals("Sales", q.sourceName());
                })
                .verifyComplete();
    }

    @Test
    void instanceLimit_refusesWithItsOwnError_evenForAnExemptUser() {
        props.setTotal(100);
        overrides.put(ME, 0L);
        instanceUsed = 99 * MB;
        StepVerifier.create(service.checkStorage(2 * MB)).expectError(InstanceQuotaExceededException.class).verify();
        StepVerifier.create(service.checkStorage(MB)).verifyComplete();
    }

    @Test
    void nothingToAdd_isNeverRefused() {
        props.setUser(1);
        props.setTotal(1);
        used.put(ME, 100 * MB);
        instanceUsed = 100 * MB;
        StepVerifier.create(service.checkStorage(0L)).verifyComplete();
        StepVerifier.create(service.checkStorage(-5L)).verifyComplete();
        StepVerifier.create(service.checkStorage(null)).verifyComplete();
    }

    @Test
    void listUsers_sortsByUsageDescending_andPages() {
        props.setUser(10);
        used.put("a@x", 1 * MB);
        used.put("b@x", 9 * MB);
        used.put("c@x", 5 * MB);
        overrides.put("c@x", 0L);
        StepVerifier.create(service.listUsers(null, "usage", true, 0, 2))
                .assertNext(page -> {
                    assertEquals(3, page.total());
                    assertEquals(List.of("b@x", "c@x"), page.items().stream().map(QuotaUsage::username).toList());
                    assertEquals(90, page.items().getFirst().usedPercent());
                    assertNull(page.items().get(1).limitBytes());
                    assertEquals(QuotaSource.USER, page.items().get(1).source());
                })
                .verifyComplete();
    }

    @Test
    void listUsers_percentSort_putsUnlimitedLast_andSearchFilters() {
        props.setUser(10);
        used.put("alice@x", 2 * MB);
        used.put("bob@x", 8 * MB);
        overrides.put("bob@x", 0L);
        StepVerifier.create(service.listUsers("ALI", "percent", true, 0, 25))
                .assertNext(page -> assertEquals(List.of("alice@x"), page.items().stream().map(QuotaUsage::username).toList()))
                .verifyComplete();
        StepVerifier.create(service.listUsers(null, "percent", true, 0, 25))
                .assertNext(page -> assertEquals(List.of("alice@x", "bob@x"), page.items().stream().map(QuotaUsage::username).toList()))
                .verifyComplete();
    }

    @Test
    void setUserQuota_rejectsNegativeAndAbsurdValues() {
        StepVerifier.create(service.setUserQuota("u@x", -1)).expectError(IllegalArgumentException.class).verify();
        StepVerifier.create(service.setUserQuota("u@x", DefaultStorageQuotaService.MAX_QUOTA_MB + 1))
                .expectError(IllegalArgumentException.class).verify();
    }
}
