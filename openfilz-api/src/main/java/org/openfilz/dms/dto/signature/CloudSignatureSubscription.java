package org.openfilz.dms.dto.signature;

import java.time.OffsetDateTime;

/**
 * OpenFilz Cloud Signing subscription snapshot, relayed verbatim from
 * {@code sign.openfilz.com} ({@code GET /api/v1/subscription}) for the Settings page.
 * Periods are UTC calendar months — {@code periodEnd} is when the quota resets.
 */
public record CloudSignatureSubscription(
        String status,
        String billingMode,
        Integer monthlyQuota,
        Long usedThisMonth,
        Long remaining,
        OffsetDateTime periodStart,
        OffsetDateTime periodEnd,
        Boolean hardCap,
        OffsetDateTime memberSince) {
}
