package org.openfilz.dms.dto.response;

import lombok.Builder;

@Builder
public record Settings(Integer emptyBinInterval, Integer fileQuotaMB, Integer userQuotaMB,
                       String language, String theme) {
}
