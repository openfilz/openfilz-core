package org.openfilz.dms.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.response.DocumentIntegrityRecord;
import org.openfilz.dms.service.ChecksumService;
import org.openfilz.dms.service.DocumentIntegrityService;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Writes and reads the append-only integrity ledger (C2).
 * <p>
 * Plain {@code @Service} with no bean condition: it is only ever called from the checksum-enabled
 * save path, so there is nothing to switch here, and a {@code @ConditionalOnProperty} would be
 * evaluated at build time in a GraalVM native image.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumentIntegrityServiceImpl implements DocumentIntegrityService, UserInfoService {

    /** Currently the only algorithm the platform computes. Stored so a future change stays readable. */
    static final String ALGORITHM = "SHA-256";

    private static final String INSERT = """
            INSERT INTO document_integrity (document_id, storage_path, version_id, algorithm, hash, recorded_by)
            VALUES (:documentId, :storagePath, :versionId, :algorithm, :hash, :recordedBy)
            """;

    private static final String SELECT_BY_DOCUMENT = """
            SELECT document_id, storage_path, version_id, algorithm, hash, recorded_at, recorded_by
            FROM document_integrity WHERE document_id = :documentId ORDER BY recorded_at DESC, id DESC
            """;

    private final DatabaseClient databaseClient;

    @Override
    public Mono<Void> record(UUID documentId, String storagePath, String versionId, String hash) {
        if (documentId == null || hash == null) {
            // Nothing provable to record. Never guess a fingerprint.
            return Mono.empty();
        }
        return getConnectedUserEmail()
                .defaultIfEmpty(ANONYMOUS_USER)
                .flatMap(user -> databaseClient.sql(INSERT)
                        .bind("documentId", documentId)
                        .bind("storagePath", storagePath == null ? "" : storagePath)
                        .bind("versionId", versionId == null ? "" : versionId)
                        .bind("algorithm", ALGORITHM)
                        .bind("hash", hash)
                        .bind("recordedBy", user)
                        .fetch().rowsUpdated()
                        .doOnNext(_ -> log.debug("Integrity record written for document {} ({})", documentId, hash))
                        .then());
    }

    @Override
    public Flux<DocumentIntegrityRecord> history(UUID documentId) {
        return databaseClient.sql(SELECT_BY_DOCUMENT)
                .bind("documentId", documentId)
                .map((row, _) -> new DocumentIntegrityRecord(
                        row.get("document_id", UUID.class),
                        emptyToNull(row.get("storage_path", String.class)),
                        emptyToNull(row.get("version_id", String.class)),
                        row.get("algorithm", String.class),
                        row.get("hash", String.class),
                        row.get("recorded_at", OffsetDateTime.class),
                        row.get("recorded_by", String.class)))
                .all();
    }

    @Override
    public Mono<DocumentIntegrityRecord> latest(UUID documentId) {
        return history(documentId).next();
    }

    /** The columns are NOT NULL for simplicity of binding; absent values read back as null. */
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** Convenience for callers holding the checksum in metadata form. */
    public static String algorithmKey() {
        return ChecksumService.HASH_SHA256_KEY;
    }
}
