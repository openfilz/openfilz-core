package org.openfilz.dms.dto.response.quota;

/**
 * One user's storage: what they use against what they may use.
 *
 * @param username       the principal storage is charged to (the e-mail claim)
 * @param displayName    a human name when the deployment knows one (null otherwise)
 * @param usedBytes      the size of the user's active files
 * @param limitBytes     the effective limit in bytes — {@code null} = unlimited
 * @param source         which level {@code limitBytes} comes from
 * @param sourceName     the name of the group it is inherited from ({@code source == GROUP}), else null
 * @param overrideMb     the user's own limit in MB when an administrator set one (0 = exempt), else null
 * @param usedPercent    percentage of the limit in use (may exceed 100 after a limit was lowered), null when unlimited
 */
public record QuotaUsage(String username,
                         String displayName,
                         long usedBytes,
                         Long limitBytes,
                         QuotaSource source,
                         String sourceName,
                         Long overrideMb,
                         Integer usedPercent) {

    public static QuotaUsage of(String username, String displayName, long usedBytes, Long limitBytes,
                                QuotaSource source, String sourceName, Long overrideMb) {
        Integer percent = limitBytes == null || limitBytes <= 0 ? null
                : (int) Math.min(Integer.MAX_VALUE, Math.round(usedBytes * 100.0 / limitBytes));
        return new QuotaUsage(username, displayName, usedBytes, limitBytes, source, sourceName, overrideMb, percent);
    }
}
