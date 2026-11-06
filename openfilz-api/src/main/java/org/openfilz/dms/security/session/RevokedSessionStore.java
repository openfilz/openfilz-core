package org.openfilz.dms.security.session;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * The Keycloak sessions (by {@code sid}) that were signed out while their access tokens were still
 * valid. Kept in {@code revoked_auth_session} so that every API replica refuses them, whichever one
 * received the back-channel logout.
 *
 * <p>Every authenticated request asks {@link #isRevoked(String)}, so the answers are cached: a
 * revoked session stays known until its row expires, and "not revoked" is trusted for
 * {@code openfilz.security.revoked-session.cache-ttl} before the table is asked again. A session
 * revoked through this replica is refused at once; on another replica, within that delay. The
 * requests of one session that arrive together (a page load) share one query.
 */
@Slf4j
@Component
public class RevokedSessionStore {

    /** Bounds the memory held by each cache; an evicted answer is simply asked from the table again. */
    static final int MAX_CACHED_SESSIONS = 50_000;

    private static final String UPSERT = """
            INSERT INTO revoked_auth_session (sid, revoked_at, expires_at)
            VALUES (:sid, now(), :expiresAt)
            ON CONFLICT (sid) DO UPDATE SET expires_at = EXCLUDED.expires_at""";

    private static final String PURGE_EXPIRED = "DELETE FROM revoked_auth_session WHERE expires_at < now()";

    private static final String FIND =
            "SELECT expires_at FROM revoked_auth_session WHERE sid = :sid AND expires_at > now()";

    private final DatabaseClient databaseClient;
    private final Duration retention;

    /** sid → when its revocation can be forgotten. An entry never outlives the row it stands for. */
    private final Cache<String, Instant> revoked;
    /**
     * sid → what the table last answered. Concurrent callers of one sid get the same future, so
     * they cost one query; a failed query is not kept.
     */
    private final AsyncCache<String, Boolean> lookups;

    @Autowired
    public RevokedSessionStore(DatabaseClient databaseClient,
                               @Value("${openfilz.security.revoked-session.retention:PT12H}") Duration retention,
                               @Value("${openfilz.security.revoked-session.cache-ttl:PT15S}") Duration lookupCacheTtl) {
        this.databaseClient = databaseClient;
        this.retention = retention;
        this.revoked = Caffeine.newBuilder()
                .expireAfterWrite(retention)
                .maximumSize(MAX_CACHED_SESSIONS)
                .build();
        this.lookups = Caffeine.newBuilder()
                .expireAfterWrite(lookupCacheTtl)
                .maximumSize(MAX_CACHED_SESSIONS)
                .buildAsync();
    }

    /** Records that the session {@code sid} was signed out: its tokens are refused from now on. */
    public Mono<Void> revoke(String sid) {
        Instant expiresAt = Instant.now().plus(retention);
        revoked.put(sid, expiresAt);
        return save(sid, expiresAt);
    }

    /**
     * Whether the session {@code sid} was signed out. If the table cannot be read the answer is
     * "not revoked": the API cannot serve the request without its database anyway, and refusing
     * every token during a blip would sign everybody out.
     */
    public Mono<Boolean> isRevoked(String sid) {
        Instant revokedUntil = revoked.getIfPresent(sid);
        if (revokedUntil != null) {
            if (revokedUntil.isAfter(Instant.now())) {
                return Mono.just(true);
            }
            revoked.invalidate(sid);
        }
        CompletableFuture<Boolean> lookup = lookups.get(sid, (key, executor) -> lookUp(key).toFuture());
        if (lookup.isDone() && !lookup.isCompletedExceptionally()) {
            return Mono.just(lookup.join()); // the usual case: answered from memory, nothing to subscribe to
        }
        // A caller giving up must not cancel the query the other requests of the session wait on.
        return Mono.fromFuture(lookup, true)
                .onErrorResume(e -> {
                    log.warn("Could not check whether session {} was signed out: {}", sid, e.getMessage());
                    return Mono.just(false);
                });
    }

    private Mono<Boolean> lookUp(String sid) {
        return findExpiry(sid)
                .map(expiresAt -> {
                    expiresAt.ifPresent(until -> revoked.put(sid, until));
                    return expiresAt.isPresent();
                });
    }

    /** When the revocation of {@code sid} expires; empty when the session is not revoked. */
    Mono<Optional<Instant>> findExpiry(String sid) {
        return databaseClient.sql(FIND)
                .bind("sid", sid)
                .map((row, meta) -> row.get("expires_at", OffsetDateTime.class))
                .one()
                .map(expiresAt -> Optional.of(expiresAt.toInstant()))
                .defaultIfEmpty(Optional.empty());
    }

    /** Writes the revocation, then drops the rows whose time has passed. */
    Mono<Void> save(String sid, Instant expiresAt) {
        return databaseClient.sql(UPSERT)
                .bind("sid", sid)
                .bind("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .then()
                .then(databaseClient.sql(PURGE_EXPIRED).then());
    }
}
