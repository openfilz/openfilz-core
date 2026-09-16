package org.openfilz.dms.dto.request;

/**
 * File everything lying loose in the caller's Inbox.
 *
 * @param allowNewFolders null = the user's preference
 */
public record AutoFileInboxRequest(Boolean allowNewFolders) {
}
