package org.openfilz.dms.dto.response;

/**
 * @param autoFileAvailable true when the deployment runs smart filing at all (the switch is shown)
 * @param autoFile          the user's switch
 * @param autoFileNewFolders whether filing may create folders (capped by the deployment's allow-new-folders)
 */
public record AiPreferencesView(boolean autoFileAvailable, boolean autoFile, boolean autoFileNewFolders) {
}
