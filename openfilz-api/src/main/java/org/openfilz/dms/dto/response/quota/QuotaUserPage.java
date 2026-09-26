package org.openfilz.dms.dto.response.quota;

import java.util.List;

/** One page of the administrators' user quota listing. */
public record QuotaUserPage(long total, int page, int size, List<QuotaUsage> items) {
}
