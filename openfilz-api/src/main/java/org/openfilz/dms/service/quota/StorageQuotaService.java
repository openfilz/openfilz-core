package org.openfilz.dms.service.quota;

import org.openfilz.dms.dto.response.quota.MyStorageQuota;
import org.openfilz.dms.dto.response.quota.QuotaOverview;
import org.openfilz.dms.dto.response.quota.QuotaUsage;
import org.openfilz.dms.dto.response.quota.QuotaUserPage;
import reactor.core.publisher.Mono;

/**
 * The one place storage quotas are resolved and enforced.
 *
 * <p>Three limits apply to anything that adds bytes (upload, TUS, replace, unzip, restore from the
 * recycle bin, PDF tools through the upload path):</p>
 * <ol>
 *   <li>the per-file limit {@code openfilz.quota.file-upload} — HTTP 413;</li>
 *   <li>the user's effective limit — HTTP 507 {@code UserQuotaExceeded}. Most specific wins: the
 *       user's own limit (set by an administrator) &gt; a limit inherited from a group
 *       (an edition hook, none in the core) &gt; the default {@code openfilz.quota.user};</li>
 *   <li>the instance limit {@code openfilz.quota.total} — HTTP 507 {@code InstanceQuotaExceeded}.</li>
 * </ol>
 * At every level 0 means "no limit".
 */
public interface StorageQuotaService {

    /** 413 when {@code size} is above the per-file limit; {@code null} size = unknown, not checked. */
    Mono<Void> checkFileSize(String filename, Long size);

    /**
     * 507 when {@code additionalBytes} more would take the calling user past their effective limit,
     * or the instance past its total. {@code null} or ≤ 0 = nothing to check.
     */
    Mono<Void> checkStorage(Long additionalBytes);

    /** {@link #checkFileSize} then {@link #checkStorage} — what a new upload of {@code size} bytes must pass. */
    default Mono<Void> checkUpload(String filename, Long size) {
        return checkFileSize(filename, size).then(checkStorage(size));
    }

    /** The per-file limit in bytes, null when there is none. */
    Long maxFileSizeBytes();

    /** The calling user's usage and effective limit. */
    Mono<MyStorageQuota> myQuota();

    /** One user's usage and effective limit (administration). */
    Mono<QuotaUsage> usage(String username);

    /**
     * The users storage is charged to, with usage and effective limit (administration).
     *
     * @param search optional case-insensitive filter on the user name / display name
     * @param sort   {@code usage} (default), {@code percent}, {@code limit} or {@code name}
     * @param desc   descending order
     */
    Mono<QuotaUserPage> listUsers(String search, String sort, boolean desc, int page, int size);

    /** Sets one user's own limit in MB (0 = no limit for this user). */
    Mono<QuotaUsage> setUserQuota(String username, long quotaMb);

    /** Removes one user's own limit: the inherited or default one applies again. */
    Mono<QuotaUsage> clearUserQuota(String username);

    /** Deployment-wide defaults and instance usage (administration). */
    Mono<QuotaOverview> overview();

    /** Size of every active file of the instance. */
    Mono<Long> instanceUsedBytes();
}
