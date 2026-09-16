package org.openfilz.dms.dto.request;

/**
 * The user's smart-filing preferences; a null field leaves the current value unchanged.
 *
 * @param inbox true creates (or reuses) the user's Inbox folder at their root, named in the request's
 *              {@code Accept-Language}; false forgets it (the folder itself is never deleted)
 */
public record SaveAiPreferencesRequest(Boolean autoFile, Boolean autoFileNewFolders, Boolean inbox) {
}
