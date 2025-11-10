package org.openfilz.dms.dto.request;

import org.openfilz.dms.enums.SortOrder;

public record SortInput(String field, SortOrder order) {}