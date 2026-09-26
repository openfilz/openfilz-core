package org.openfilz.dms.dto.response.quota;

/**
 * The caller's own quota, for the end-user screens (dashboard ring, settings card).
 *
 * @param usedBytes        the size of the caller's active files
 * @param limitBytes       the caller's effective limit — {@code null} = unlimited
 * @param source           where that limit comes from
 * @param sourceName       the group it is inherited from, when {@code source == GROUP}
 * @param maxFileSizeBytes the largest single upload accepted — {@code null} = no per-file limit
 */
public record MyStorageQuota(long usedBytes,
                             Long limitBytes,
                             QuotaSource source,
                             String sourceName,
                             Long maxFileSizeBytes) {
}
