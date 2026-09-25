package org.openfilz.dms.dto.response.quota;

/**
 * Where a user's effective storage limit comes from — the most specific level that has one wins:
 * {@link #USER} (an administrator set this user's own limit) &gt; {@link #GROUP} (inherited from a
 * grouping of users an edition built on this API defines, see
 * {@code DefaultStorageQuotaService#inheritedQuotas}) &gt; {@link #DEFAULT} ({@code openfilz.quota.user}).
 */
public enum QuotaSource {
    USER,
    GROUP,
    DEFAULT
}
