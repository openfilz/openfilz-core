package org.openfilz.dms.event;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Published (Spring application event) when a document's tier-2 insight is stored. Extension
 * layers turn it into an outbound webhook ({@code document.insights.ready}).
 */
public record DocumentInsightsReadyEvent(UUID documentId, String name, String category, String summary,
                                         String language, List<String> keywords, Map<String, Object> entities) {
}
