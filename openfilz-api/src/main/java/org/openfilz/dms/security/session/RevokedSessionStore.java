package org.openfilz.dms.security.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Keycloak sessions (by {@code sid}) that were signed out while their access tokens were still
 * valid. Kept in {@code revoked_auth_session} so that every API replica refuses them, whichever one
 * received the back-channel logout.
 *
 * <p>Every authenticated request asks {@link #isRevoked(String)}, so the answers are cached: a
 * revoked session stays known until its row expires, and "not revoked" is trusted for
 * {@code openfilz.security.revoked-session.cache-ttl} before the table is asked again. A session
 * revoked through this replica is refused at once; on another replica, within that delay.
 */
@Slf4j
@Component
public class RevokedSessionStore {

    /** Bounds the memory held for "not revoked" answers; past it that cache is simply emptied. */
    static final int MAX_CACHED_ACTIVE = 50_000;

    private static final String UPSERT = """
            INSERT INTO revoked_auth_session (sid, revoked_at, expires_at)
            VALUES (:sid, now(), :expiresAt)
            ON CONFLICT (sid) DO UPDATE SET expires_at = EXCLUDED.expires_at""";

    private static final String PURGE_EXPIRED = "DELETE FROM revoked_auth_session WHERE expires_at < now()";

    private static final String FIND =
            "SELECT expires_at FROM revoked_auth_session WHERE sid = :sid AND expires_at > now()";

    private final DatabaseClient databaseClient;
    private final Duration retention;
    private final long activeCacheTtlMillis;

    /** sid → epoch millis after which the revocation can be forgotten. */
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();
    /** sid → epoch millis when the table last said the session was not revoked. */
    private final Map<String, Long> active = new ConcurrentHashMap<>();

    public RevokedSessionStore(DatabaseClient databaseClient,
                               @Value("${openfilz.security.revoked-session.retention:PT12H}") Duration retention,
                               @Value("${openfilz.security.revoked-session.cache-ttl:PT15S}") Duration activeCacheTtl) {
        this.databaseClient = databaseClient;
        this.retention = retention;
        this.activeCacheTtlMillis = activeCacheTtl.toMillis();
    }

    /** Records that the session {@code sid} was signed out: its tokens are refused from now on. */
    public Mono<Void> revoke(String sid) {
        Instant expiresAt = Instant.now().plus(retention);
        revoked.put(sid, expiresAt.toEpochMilli());
        active.remove(sid);
        return databaseClient.sql(UPSERT)
                .bind("sid", sid)
                .bind("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                .then()
                .then(databaseClient.sql(PURGE_EXPIRED).then());
    }

    /**
     * Whether the session {@code sid} was signed out. If the table cannot be read the answer is
     * "not revoked": the API cannot serve the request without its database anyway, and refusing
     * every token during a blip would sign everybody out.
     */
    public Mono<Boolean> isRevoked(String sid) {
        long now = System.currentTimeMillis();
        Long revokedUntil = revoked.get(sid);
        if (revokedUntil != null) {
            if (revokedUntil > now) {
                return Mono.just(true);
            }
            revoked.remove(sid);
        }
        Long checkedAt = active.get(sid);
        if (checkedAt != null && now - checkedAt < activeCacheTtlMillis) {
            return Mono.just(false);
        }
        return databaseClient.sql(FIND)
                .bind("sid", sid)
                .map((row, meta) -> row.get("expires_at", OffsetDateTime.class))
                .one()
                .map(expiresAt -> {
                    revoked.put(sid, expiresAt.toInstant().toEpochMilli());
                    active.remove(sid);
                    return true;
                })
                .switchIfEmpty(Mono.fromSupplier(() -> {
                    if (active.size() >= MAX_CACHED_ACTIVE) {
                        active.clear();
                    }
                    active.put(sid, now);
                    return false;
                }))
                .onErrorResume(e -> {
                    log.warn("Could not check whether session {} was signed out: {}", sid, e.getMessage());
                    return Mono.just(false);
                });
    }
}
