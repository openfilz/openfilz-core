package org.openfilz.dms.dto.request;

import jakarta.validation.constraints.NotNull;
import org.openfilz.dms.enums.SortOrder;

public record PageCriteria(String sortBy,
                           SortOrder sortOrder,
                           @NotNull Integer pageNumber,
                           @NotNull Integer pageSize
                           ) {
}
