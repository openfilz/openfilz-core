package org.openfilz.dms.repository;

import org.openfilz.dms.enums.DocumentType;
import reactor.core.publisher.Mono;

public interface StatisticsDAO {

    Mono<Long> countFilesByType(DocumentType type);

    Mono<Long> getTotalStorageByContentType(String contentTypePattern);

    Mono<Long> countFilesByContentType(String contentTypePattern);

    Mono<Long> getTotalStorageUsed();
}
