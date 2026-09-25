package org.openfilz.dms.service.quota;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.QuotaProperties;
import org.openfilz.dms.dto.response.quota.MyStorageQuota;
import org.openfilz.dms.dto.response.quota.QuotaOverview;
import org.openfilz.dms.dto.response.quota.QuotaSource;
import org.openfilz.dms.dto.response.quota.QuotaUsage;
import org.openfilz.dms.dto.response.quota.QuotaUserPage;
import org.openfilz.dms.exception.FileSizeExceededException;
import org.openfilz.dms.exception.InstanceQuotaExceededException;
import org.openfilz.dms.exception.UserQuotaExceededException;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Core {@link StorageQuotaService}: the per-user override lives in {@code user_storage_quota}
 * (V1_15), the defaults in {@link QuotaProperties} (read per call, so a runtime change of the
 * properties applies at once), usage is summed on the fly.
 *
 * <p>Extension seams (all {@code protected}, safe defaults):</p>
 * <ul>
 *   <li>{@link #usedBytes(String)} — what a user is charged: in the core, the active files they
 *       created ({@code documents.created_by});</li>
 *   <li>{@link #inheritedQuotas(Collection)} — limits users inherit from a grouping the core does not
 *       model; none here;</li>
 *   <li>{@link #chargedUsers()} — who the administrators' listing shows: in the core, everyone who
 *       created a file or has an override.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DefaultStorageQuotaService implements StorageQuotaService, UserInfoService {

    protected static final long MB = 1024L * 1024L;
    /** 1 PB — above that a value is a typo, not a quota. */
    protected static final long MAX_QUOTA_MB = 1024L * 1024L * 1024L;

    /** A limit a user inherits from a group: {@code quotaMb} (0 = no limit) and the group's name. */
    public record InheritedQuota(long quotaMb, String name) {
    }

    /** One row of the administrators' listing before limits are resolved. */
    public record UserStorageRow(String username, String displayName, long usedBytes) {
    }

    private static final String USED_BY_CREATOR = """
            SELECT created_by AS username, COALESCE(SUM(size), 0) AS used
            FROM documents
            WHERE type = 'FILE' AND active = true AND created_by IS NOT NULL
            GROUP BY created_by""";

    private static final String INSTANCE_USED = """
            SELECT COALESCE(SUM(size), 0) AS used FROM documents WHERE type = 'FILE' AND active = true""";

    private static final String SELECT_OVERRIDE = "SELECT quota_mb FROM user_storage_quota WHERE username = :username";
    private static final String SELECT_OVERRIDES = "SELECT username, quota_mb FROM user_storage_quota";
    private static final String COUNT_OVERRIDES = "SELECT COUNT(*) AS n FROM user_storage_quota";
    private static final String UPSERT_OVERRIDE = """
            INSERT INTO user_storage_quota (username, quota_mb, updated_by, updated_at)
            VALUES (:username, :quotaMb, :updatedBy, now())
            ON CONFLICT (username) DO UPDATE
               SET quota_mb = EXCLUDED.quota_mb, updated_by = EXCLUDED.updated_by, updated_at = now()""";
    private static final String DELETE_OVERRIDE = "DELETE FROM user_storage_quota WHERE username = :username";

    protected final QuotaProperties quotaProperties;
    protected final DocumentDAO documentDAO;
    protected final DatabaseClient databaseClient;

    // ------------------------------------------------------------------ seams

    /** What {@code username} is charged today. Core: their active files ({@code created_by}). */
    protected Mono<Long> usedBytes(String username) {
        return documentDAO.getTotalStorageByUser(username);
    }

    /**
     * Limits the given users inherit from a grouping of users the core does not know (none here).
     * A user absent from the map inherits nothing. Called with one user on every upload, with a
     * whole page for the administrators' listing — implement it with one query.
     */
    protected Mono<Map<String, InheritedQuota>> inheritedQuotas(Collection<String> usernames) {
        return Mono.just(Map.of());
    }

    /**
     * The users the administrators' listing shows. Core: every creator of an active file plus every
     * user with an override (who may have nothing yet).
     */
    protected Flux<UserStorageRow> chargedUsers() {
        Flux<UserStorageRow> creators = databaseClient.sql(USED_BY_CREATOR)
                .map(row -> new UserStorageRow(row.get("username", String.class), null, longValue(row.get("used", Number.class))))
                .all();
        Flux<UserStorageRow> overridden = databaseClient.sql(SELECT_OVERRIDES)
                .map(row -> new UserStorageRow(row.get("username", String.class), null, 0L))
                .all();
        // Creators first: their row carries the usage and wins over the zero-usage override row.
        return Flux.concat(creators, overridden).distinct(UserStorageRow::username);
    }

    // ------------------------------------------------------------------ enforcement

    @Override
    public Mono<Void> checkFileSize(String filename, Long size) {
        Long max = positive(quotaProperties.getFileUploadQuotaInBytes());
        if (max == null || size == null || size <= max) {
            return Mono.empty();
        }
        return Mono.error(new FileSizeExceededException(filename, size, max));
    }

    @Override
    public Long maxFileSizeBytes() {
        return positive(quotaProperties.getFileUploadQuotaInBytes());
    }

    @Override
    public Mono<Void> checkStorage(Long additionalBytes) {
        if (additionalBytes == null || additionalBytes <= 0) {
            return Mono.empty();
        }
        return getConnectedUserEmail()
                .flatMap(username -> checkUser(username, additionalBytes))
                .then(Mono.defer(() -> checkInstance(additionalBytes)));
    }

    private Mono<Void> checkUser(String username, long additionalBytes) {
        return limit(username)
                .flatMap(limit -> limit.limitBytes() == null
                        ? Mono.<Void>empty()
                        : usedBytes(username).flatMap(used -> used + additionalBytes > limit.limitBytes()
                                ? Mono.<Void>error(new UserQuotaExceededException(username, used, additionalBytes, limit.limitBytes()))
                                : Mono.<Void>empty()));
    }

    private Mono<Void> checkInstance(long additionalBytes) {
        Long max = positive(quotaProperties.getTotalQuotaInBytes());
        if (max == null) {
            return Mono.empty();
        }
        return instanceUsedBytes().flatMap(used -> used + additionalBytes > max
                ? Mono.<Void>error(new InstanceQuotaExceededException(used, additionalBytes, max))
                : Mono.<Void>empty());
    }

    // ------------------------------------------------------------------ resolution

    /** The effective limit of one user (limitBytes null = unlimited). */
    private record Limit(Long limitBytes, QuotaSource source, String sourceName, Long overrideMb) {
    }

    private Mono<Limit> limit(String username) {
        return override(username)
                .zipWith(inheritedQuotas(List.of(username)).defaultIfEmpty(Map.of()))
                .map(t -> resolve(t.getT1().orElse(null), t.getT2().get(username)));
    }

    private Limit resolve(Long overrideMb, InheritedQuota inherited) {
        if (overrideMb != null) {
            return new Limit(toBytes(overrideMb), QuotaSource.USER, null, overrideMb);
        }
        if (inherited != null) {
            return new Limit(toBytes(inherited.quotaMb()), QuotaSource.GROUP, inherited.name(), null);
        }
        return new Limit(positive(quotaProperties.getUserQuotaInBytes()), QuotaSource.DEFAULT, null, null);
    }

    private static Long toBytes(long mb) {
        return mb <= 0 ? null : mb * MB;
    }

    /** The user's own limit in MB (0 = exempt) when an administrator set one. */
    protected Mono<Optional<Long>> override(String username) {
        return databaseClient.sql(SELECT_OVERRIDE)
                .bind("username", username)
                .map(row -> longValue(row.get("quota_mb", Long.class)))
                .one()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty());
    }

    /** Every user override (username → MB). */
    protected Mono<Map<String, Long>> overrides() {
        return databaseClient.sql(SELECT_OVERRIDES)
                .map(row -> Map.entry(row.get("username", String.class), longValue(row.get("quota_mb", Long.class))))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private QuotaUsage toUsage(UserStorageRow row, Limit limit) {
        return QuotaUsage.of(row.username(), row.displayName(), row.usedBytes(), limit.limitBytes(),
                limit.source(), limit.sourceName(), limit.overrideMb());
    }

    // ------------------------------------------------------------------ reads

    @Override
    public Mono<MyStorageQuota> myQuota() {
        return getConnectedUserEmail()
                .flatMap(username -> Mono.zip(limit(username), usedBytes(username))
                        .map(t -> new MyStorageQuota(t.getT2(), t.getT1().limitBytes(), t.getT1().source(),
                                t.getT1().sourceName(), maxFileSizeBytes())));
    }

    @Override
    public Mono<QuotaUsage> usage(String username) {
        String user = requireUsername(username);
        return Mono.zip(limit(user), usedBytes(user), displayName(user))
                .map(t -> toUsage(new UserStorageRow(user, t.getT3().orElse(null), t.getT2()), t.getT1()));
    }

    /** A human name for {@code username}, when the deployment knows one. Core: none. */
    protected Mono<Optional<String>> displayName(String username) {
        return Mono.just(Optional.empty());
    }

    @Override
    public Mono<QuotaUserPage> listUsers(String search, String sort, boolean desc, int page, int size) {
        int safeSize = Math.clamp(size <= 0 ? 25 : size, 1, 500);
        int safePage = Math.max(0, page);
        String needle = search == null || search.isBlank() ? null : search.strip().toLowerCase(Locale.ROOT);
        return chargedUsers()
                .filter(r -> needle == null
                        || r.username().toLowerCase(Locale.ROOT).contains(needle)
                        || (r.displayName() != null && r.displayName().toLowerCase(Locale.ROOT).contains(needle)))
                .collectList()
                .flatMap(rows -> Mono.zip(overrides(),
                                inheritedQuotas(rows.stream().map(UserStorageRow::username).toList()).defaultIfEmpty(Map.of()))
                        .map(t -> rows.stream()
                                .map(r -> toUsage(r, resolve(t.getT1().get(r.username()), t.getT2().get(r.username()))))
                                .sorted(comparator(sort, desc))
                                .toList()))
                .map(all -> {
                    int from = Math.min(all.size(), safePage * safeSize);
                    int to = Math.min(all.size(), from + safeSize);
                    return new QuotaUserPage(all.size(), safePage, safeSize, all.subList(from, to));
                });
    }

    /** Unlimited sorts as the largest limit and the lowest percentage. */
    static Comparator<QuotaUsage> comparator(String sort, boolean desc) {
        Comparator<QuotaUsage> byName = Comparator.comparing(u -> u.username().toLowerCase(Locale.ROOT));
        Comparator<QuotaUsage> c = switch (sort == null ? "usage" : sort.toLowerCase(Locale.ROOT)) {
            case "name" -> byName;
            case "limit" -> Comparator.comparing((QuotaUsage u) -> u.limitBytes() == null ? Long.MAX_VALUE : u.limitBytes());
            case "percent" -> Comparator.comparing((QuotaUsage u) -> u.usedPercent() == null ? -1 : u.usedPercent());
            default -> Comparator.comparingLong(QuotaUsage::usedBytes);
        };
        return (desc ? c.reversed() : c).thenComparing(byName);
    }

    @Override
    public Mono<QuotaOverview> overview() {
        return Mono.zip(instanceUsedBytes(),
                        databaseClient.sql(COUNT_OVERRIDES).map(row -> longValue(row.get("n", Long.class))).one().defaultIfEmpty(0L))
                .map(t -> new QuotaOverview(
                        intValue(quotaProperties.getFileUpload()),
                        intValue(quotaProperties.getUser()),
                        intValue(quotaProperties.getTotal()),
                        t.getT1(),
                        positive(quotaProperties.getTotalQuotaInBytes()),
                        t.getT2()));
    }

    @Override
    public Mono<Long> instanceUsedBytes() {
        return databaseClient.sql(INSTANCE_USED)
                .map(row -> longValue(row.get("used", Number.class)))
                .one()
                .defaultIfEmpty(0L);
    }

    // ------------------------------------------------------------------ writes

    @Override
    public Mono<QuotaUsage> setUserQuota(String username, long quotaMb) {
        String user = requireUsername(username);
        if (quotaMb < 0 || quotaMb > MAX_QUOTA_MB) {
            return Mono.error(new IllegalArgumentException("quotaMb must be between 0 (no limit) and " + MAX_QUOTA_MB));
        }
        return getConnectedUserEmail()
                .flatMap(admin -> databaseClient.sql(UPSERT_OVERRIDE)
                        .bind("username", user)
                        .bind("quotaMb", quotaMb)
                        .bind("updatedBy", admin)
                        .fetch().rowsUpdated()
                        .doOnSuccess(_ -> log.info("Storage quota of {} set to {} MB by {}", user, quotaMb, admin)))
                .then(Mono.defer(() -> usage(user)));
    }

    @Override
    public Mono<QuotaUsage> clearUserQuota(String username) {
        String user = requireUsername(username);
        return getConnectedUserEmail()
                .flatMap(admin -> databaseClient.sql(DELETE_OVERRIDE)
                        .bind("username", user)
                        .fetch().rowsUpdated()
                        .doOnSuccess(_ -> log.info("Storage quota override of {} removed by {}", user, admin)))
                .then(Mono.defer(() -> usage(user)));
    }

    // ------------------------------------------------------------------ helpers

    /** null for "no limit" (null, 0 or negative). */
    protected static Long positive(Long bytes) {
        return bytes == null || bytes <= 0 ? null : bytes;
    }

    protected static String requireUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 255
                || username.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("A user name of 1 to 255 characters is required");
        }
        return username.strip();
    }

    protected static long longValue(Number n) {
        return n == null ? 0L : n.longValue();
    }

    private static int intValue(Integer n) {
        return n == null ? 0 : n;
    }
}
