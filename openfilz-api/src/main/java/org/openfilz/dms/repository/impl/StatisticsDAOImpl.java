package org.openfilz.dms.repository.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.StatisticsDAO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.entity.SqlTableMapping.DOCUMENT;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class StatisticsDAOImpl implements StatisticsDAO {

    private final DatabaseClient databaseClient;

    @Override
    public Mono<Long> countFilesByType(DocumentType type) {
        return databaseClient.sql("SELECT COUNT(*) FROM " + DOCUMENT + " WHERE " + TYPE + " = :type")
                .bind("type", type.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> getTotalStorageByContentType(String contentTypePattern) {
        return databaseClient.sql("SELECT COALESCE(SUM(" + SIZE + "), 0) FROM " + DOCUMENT +
                        " WHERE " + CONTENT_TYPE + " LIKE :pattern AND " + TYPE + " = :type")
                .bind("pattern", contentTypePattern)
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> countFilesByContentType(String contentTypePattern) {
        return databaseClient.sql("SELECT COUNT(*) FROM " + DOCUMENT +
                        " WHERE " + CONTENT_TYPE + " LIKE :pattern AND " + TYPE + " = :type")
                .bind("pattern", contentTypePattern)
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> getTotalStorageUsed() {
        return databaseClient.sql("SELECT COALESCE(SUM(" + SIZE + "), 0) FROM " + DOCUMENT +
                        " WHERE " + TYPE + " = :type")
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

}
