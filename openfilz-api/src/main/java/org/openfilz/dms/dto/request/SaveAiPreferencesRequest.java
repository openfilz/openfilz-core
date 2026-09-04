package org.openfilz.dms.dto.request;

/** The user's smart-filing preferences; a null field leaves the current value unchanged. */
public record SaveAiPreferencesRequest(Boolean autoFile, Boolean autoFileNewFolders) {
}
