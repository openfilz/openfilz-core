package org.openfilz.dms.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * The jobs of one upload batch, followed in a single call. The browser fires one upload request
 * per file, so a batch of two hundred files leaves two hundred filing jobs to follow: polling
 * them one by one was two hundred requests every couple of seconds.
 */
public record AutoFileJobsRequest(List<UUID> jobIds) {
}
