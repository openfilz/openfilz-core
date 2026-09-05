package org.openfilz.dms.service.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.AiProperties;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory cache and rate cap behind {@code planReorganization}: same user + same request
 * shape is served again, a user's entries vanish on invalidation, and productions are counted
 * per user within the window (cached hits are not productions, so they never count).
 */
class ReorganizationInventoryCacheTest {

    private static ReorganizationInventoryCache cache(int rateLimit, Duration ttl) {
        AiProperties properties = new AiProperties();
        properties.getReorganization().setPlanRateLimit(rateLimit);
        properties.getReorganization().setInventoryCacheTtl(ttl);
        properties.getReorganization().setPlanRateWindow(Duration.ofMinutes(10));
        return new ReorganizationInventoryCache(properties);
    }

    @Test
    @DisplayName("the same user and request shape is served from the cache; another shape is not")
    void servesTheCachedInventoryForTheSameShape() {
        ReorganizationInventoryCache cache = cache(20, Duration.ofMinutes(2));
        UUID root = UUID.randomUUID();
        String key = ReorganizationInventoryCache.key("Alice@Example.com", root, 4, 300, null);

        assertThat(cache.get(key)).isNull();
        cache.put(key, "inventory");

        assertThat(cache.get(key)).isEqualTo("inventory");
        assertThat(cache.get(ReorganizationInventoryCache.key("alice@example.com", root, 4, 300, null)))
                .as("the user email is normalised in the key").isEqualTo("inventory");
        assertThat(cache.get(ReorganizationInventoryCache.key("alice@example.com", root, 5, 300, null)))
                .as("a different depth is a different inventory").isNull();
        assertThat(cache.get(ReorganizationInventoryCache.key("alice@example.com", null, 4, 300, null)))
                .as("the root level is a different inventory").isNull();
    }

    @Test
    @DisplayName("invalidating a user drops every inventory of that user and nobody else's")
    void invalidateDropsOnlyThatUsersEntries() {
        ReorganizationInventoryCache cache = cache(20, Duration.ofMinutes(2));
        String aliceRoot = ReorganizationInventoryCache.key("alice@example.com", null, 4, 300, null);
        String aliceSub = ReorganizationInventoryCache.key("alice@example.com", UUID.randomUUID(), 4, 300, null);
        String bob = ReorganizationInventoryCache.key("bob@example.com", null, 4, 300, null);
        cache.put(aliceRoot, "a1");
        cache.put(aliceSub, "a2");
        cache.put(bob, "b");

        cache.invalidate("ALICE@example.com");

        assertThat(cache.get(aliceRoot)).isNull();
        assertThat(cache.get(aliceSub)).isNull();
        assertThat(cache.get(bob)).isEqualTo("b");
    }

    @Test
    @DisplayName("a zero TTL caches nothing")
    void zeroTtlCachesNothing() {
        ReorganizationInventoryCache cache = cache(20, Duration.ZERO);
        String key = ReorganizationInventoryCache.key("alice@example.com", null, 4, 300, null);
        cache.put(key, "inventory");
        assertThat(cache.get(key)).isNull();
    }

    @Test
    @DisplayName("the rate cap counts productions per user within the window")
    void rateCapCountsProductionsPerUser() {
        ReorganizationInventoryCache cache = cache(2, Duration.ofMinutes(2));

        assertThat(cache.tryAcquire("alice@example.com")).isTrue();
        assertThat(cache.tryAcquire("Alice@example.com")).isTrue();
        assertThat(cache.tryAcquire("alice@example.com")).as("third production in the window").isFalse();
        assertThat(cache.tryAcquire("bob@example.com")).as("another user has their own budget").isTrue();
        assertThat(cache.rateLimit()).isEqualTo(2);
        assertThat(cache.rateWindow()).isEqualTo(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("a non-positive limit disables the cap")
    void zeroLimitDisablesTheCap() {
        ReorganizationInventoryCache cache = cache(0, Duration.ofMinutes(2));
        for (int i = 0; i < 50; i++) {
            assertThat(cache.tryAcquire("alice@example.com")).isTrue();
        }
    }
}
