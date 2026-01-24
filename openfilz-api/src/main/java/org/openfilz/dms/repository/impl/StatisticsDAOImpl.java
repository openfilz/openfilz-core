package org.openfilz.dms.repository.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.entity.SqlColumnMapping;
import org.openfilz.dms.entity.SqlTableMapping;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.StatisticsDAO;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import static org.openfilz.dms.entity.SqlColumnMapping.ACTIVE;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
public class StatisticsDAOImpl implements StatisticsDAO {

    private final DatabaseClient databaseClient;

    private final static String AND_IS_ACTIVE = " and " + ACTIVE + " = true";
    private static final String COUNT_BY_TYPE = "SELECT COUNT(*) FROM " + SqlTableMapping.DOCUMENT + " WHERE " + SqlColumnMapping.TYPE + " = :type" + AND_IS_ACTIVE;
    private static final String STORAGE_BY_TYPE = "SELECT COALESCE(SUM(" + SqlColumnMapping.SIZE + "), 0) FROM " + SqlTableMapping.DOCUMENT +
            " WHERE " + SqlColumnMapping.CONTENT_TYPE + " LIKE :pattern AND " + SqlColumnMapping.TYPE + " = :type" + AND_IS_ACTIVE;
    private static final String FILES_BY_TYPE = "SELECT COUNT(*) FROM " + SqlTableMapping.DOCUMENT +
            " WHERE " + SqlColumnMapping.CONTENT_TYPE + " LIKE :pattern AND " + SqlColumnMapping.TYPE + " = :type" + AND_IS_ACTIVE;
    private static final String TOTAL_STORAGE = "SELECT COALESCE(SUM(" + SqlColumnMapping.SIZE + "), 0) FROM " + SqlTableMapping.DOCUMENT +
            " WHERE " + SqlColumnMapping.TYPE + " = :type" + AND_IS_ACTIVE;

    @Override
    public Mono<Long> countFilesByType(DocumentType type) {
        return databaseClient.sql(COUNT_BY_TYPE)
                .bind("type", type.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> getTotalStorageByContentType(String contentTypePattern) {
        return databaseClient.sql(STORAGE_BY_TYPE)
                .bind("pattern", contentTypePattern)
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> countFilesByContentType(String contentTypePattern) {
        return databaseClient.sql(FILES_BY_TYPE)
                .bind("pattern", contentTypePattern)
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

    @Override
    public Mono<Long> getTotalStorageUsed() {
        return databaseClient.sql(TOTAL_STORAGE)
                .bind("type", DocumentType.FILE.name())
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

}
