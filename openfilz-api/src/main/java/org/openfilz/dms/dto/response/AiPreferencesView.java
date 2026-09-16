package org.openfilz.dms.dto.response;

import java.util.UUID;

/**
 * @param autoFileAvailable  true when the deployment runs smart filing at all (the switch is shown)
 * @param autoFile           the user's switch
 * @param autoFileNewFolders whether filing may create folders (capped by the deployment's allow-new-folders)
 * @param inboxAvailable     true when the deployment offers the Inbox convention (smart filing on and {@code auto-file.inbox.enabled})
 * @param inbox              true when the user has an Inbox folder (and it still exists)
 * @param inboxFolderId      that folder, when {@code inbox} is true
 */
public record AiPreferencesView(boolean autoFileAvailable, boolean autoFile, boolean autoFileNewFolders,
                                boolean inboxAvailable, boolean inbox, UUID inboxFolderId) {
}
