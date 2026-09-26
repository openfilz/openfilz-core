package org.openfilz.dms.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Sets one user's own storage limit.
 *
 * @param quotaMb the limit in MB; 0 exempts the user (no limit). Removing the override (DELETE)
 *                gives the user the inherited or default limit back.
 */
public record UserQuotaRequest(@Schema(description = "Limit in MB; 0 = no limit for this user", example = "2048") Long quotaMb) {
}
