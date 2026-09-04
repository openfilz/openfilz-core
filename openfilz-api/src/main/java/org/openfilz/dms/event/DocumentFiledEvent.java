package org.openfilz.dms.event;

import java.util.UUID;

/**
 * Published (Spring application event) when smart filing moved a document. Extension layers turn
 * it into an outbound webhook ({@code document.filed}); core publishes it and nothing more.
 */
public record DocumentFiledEvent(UUID documentId, String name, UUID fromFolderId, UUID toFolderId, String toPath,
                                 String stage, Double confidence, String reason, String userEmail) {
}
