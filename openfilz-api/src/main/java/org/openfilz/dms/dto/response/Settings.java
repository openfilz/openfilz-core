package org.openfilz.dms.dto.response;

public record Settings(Integer emptyBinInterval, Integer fileQuotaMB, Integer userQuotaMB) {
}
