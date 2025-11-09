package org.openfilz.dms.dto.response;

import java.util.List;

public record DocumentSearchResult(
    Long totalHits,
    List<DocumentSearchInfo> documents
) {}