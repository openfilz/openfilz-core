package org.openfilz.dms.dto.response.quota;

/**
 * The deployment-wide quota picture for administrators.
 *
 * @param fileUploadMb       {@code openfilz.quota.file-upload} (0 = no limit)
 * @param defaultUserMb      {@code openfilz.quota.user} (0 = no limit)
 * @param totalMb            {@code openfilz.quota.total} (0 = no limit)
 * @param instanceUsedBytes  the size of every active file of the instance
 * @param instanceLimitBytes {@code totalMb} in bytes — null = unlimited
 * @param userOverrides      how many users have their own limit
 */
public record QuotaOverview(int fileUploadMb,
                            int defaultUserMb,
                            int totalMb,
                            long instanceUsedBytes,
                            Long instanceLimitBytes,
                            long userOverrides) {
}
