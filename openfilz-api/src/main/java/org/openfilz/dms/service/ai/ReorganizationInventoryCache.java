package org.openfilz.dms.service.ai;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AiProperties;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Short-lived cache of reorganisation inventories plus a per-user rate cap on producing them.
 * <p>
 * An inventory walks a folder subtree and, in the enterprise edition, consults the access
 * policy for every entry. The model routinely asks for the same inventory twice in a turn, and
 * an external MCP agent that retries does the same across requests, so the text is kept for
 * {@code openfilz.ai.reorganization.inventory-cache-ttl} (default 2 min) keyed by user and
 * request shape. The cache is dropped for a user as soon as one of their tool calls mutates the
 * library (chat turn or MCP call), so an agent never plans against a tree it just changed; a
 * manual move in the UI within the TTL is harmless because a plan is re-validated against the
 * live state before anything moves.
 * <p>
 * The rate cap ({@code plan-rate-limit} calls per {@code plan-rate-window}, default 20 / 10 min)
 * bounds a looping agent; cached hits do not count.
 */
@Slf4j
@Component
@Lazy
public class ReorganizationInventoryCache {

    private final Cache<String, String> inventories;
    private final Cache<String, AtomicInteger> calls;
    private final int rateLimit;
    private final Duration rateWindow;

    public ReorganizationInventoryCache(AiProperties aiProperties) {
        AiProperties.Reorganization config = aiProperties.getReorganization();
        Duration ttl = config.getInventoryCacheTtl() == null || config.getInventoryCacheTtl().isNegative()
                ? Duration.ZERO : config.getInventoryCacheTtl();
        this.inventories = Caffeine.newBuilder()
                .maximumSize(1_000)
                .expireAfterWrite(ttl)
                .build();
        this.rateLimit = config.getPlanRateLimit();
        this.rateWindow = config.getPlanRateWindow() == null || config.getPlanRateWindow().isNegative()
                ? Duration.ofMinutes(10) : config.getPlanRateWindow();
        this.calls = Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterWrite(this.rateWindow)
                .build();
    }

    /** Cache key for one inventory request shape. */
    public static String key(String userEmail, UUID rootId, int depthLimit, int itemLimit, String detail) {
        return normalize(userEmail) + "|" + (rootId == null ? "root" : rootId) + "|" + depthLimit + "|" + itemLimit
                + "|" + (detail == null ? "" : detail);
    }

    /** The cached inventory text, or null. */
    public String get(String key) {
        return inventories.getIfPresent(key);
    }

    public void put(String key, String inventory) {
        if (inventory != null) {
            inventories.put(key, inventory);
        }
    }

    /** Drop every cached inventory of this user (their library just changed). */
    public void invalidate(String userEmail) {
        String prefix = normalize(userEmail) + "|";
        inventories.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    /**
     * Count one inventory production for the user. Returns true when it may proceed, false once
     * the cap for the current window is reached. A non-positive limit disables the cap.
     */
    public boolean tryAcquire(String userEmail) {
        if (rateLimit <= 0) {
            return true;
        }
        AtomicInteger counter = calls.get(normalize(userEmail), k -> new AtomicInteger());
        int count = counter.incrementAndGet();
        if (count > rateLimit) {
            log.warn("[REORG] inventory rate cap reached for {} ({} calls in {})", userEmail, count - 1, rateWindow);
            return false;
        }
        return true;
    }

    public int rateLimit() {
        return rateLimit;
    }

    public Duration rateWindow() {
        return rateWindow;
    }

    private static String normalize(String userEmail) {
        return userEmail == null ? "" : userEmail.trim().toLowerCase(Locale.ROOT);
    }
}
