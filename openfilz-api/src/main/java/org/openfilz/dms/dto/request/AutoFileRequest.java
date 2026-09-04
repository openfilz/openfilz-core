package org.openfilz.dms.dto.request;

import java.util.List;
import java.util.UUID;

/**
 * File existing documents on demand (e.g. "file my Inbox").
 *
 * @param allowNewFolders null = the user's preference
 */
public record AutoFileRequest(List<UUID> documentIds, Boolean allowNewFolders) {
}
