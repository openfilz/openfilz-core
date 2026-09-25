package org.openfilz.dms.repository;

import org.openfilz.dms.enums.DocumentType;
import reactor.core.publisher.Mono;

public interface StatisticsDAO {

    Mono<Long> countFilesByType(DocumentType type);

    Mono<Long> getTotalStorageByContentType(String contentTypePattern);

    Mono<Long> countFilesByContentType(String contentTypePattern);

    Mono<Long> getTotalStorageUsed();

    /**
     * True when these statistics are the caller's own (an edition that scopes the dashboard to
     * what the user owns), false when they cover the whole instance (the core). Decides which limit
     * the dashboard puts next to {@link #getTotalStorageUsed()}: the caller's quota or the
     * instance's.
     */
    default boolean isScopedToCaller() {
        return false;
    }
}
