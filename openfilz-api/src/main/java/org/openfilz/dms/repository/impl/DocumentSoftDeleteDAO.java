package org.openfilz.dms.repository.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.SqlQueryUtils;
import org.openfilz.dms.utils.SqlUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.entity.SqlTableMapping.RECYCLE_BIN;

@Service
@RequiredArgsConstructor
@ConditionalOnProperties(value = {
        @ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true"),
        @ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
})
public class DocumentSoftDeleteDAO implements UserInfoService, SqlQueryUtils {

    public static final String SOFT_DELETE_DOC = "UPDATE documents SET active = false where id = :id";
    public static final String INSERT_INTO_RECYCLE_BIN = "insert into recycle_bin(id, deleted_by) values ($1, $2)";
    public static final String RECURSIVE_SET_DOCS_INACTIVE = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM documents WHERE id = :docId
                UNION ALL
                SELECT d.id FROM documents d
                INNER JOIN descendants parent ON d.parent_id = parent.id
            )
            UPDATE documents
            SET active = false
            WHERE id IN (SELECT id FROM descendants)""";
    public static final String RECURSIVE_INSERT_IN_RECYCLE_BIN = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM documents WHERE id = :docId and active = true
                UNION ALL
                SELECT d.id FROM documents d
                INNER JOIN descendants parent ON d.parent_id = parent.id
                WHERE d.active = true
            )
            INSERT INTO recycle_bin(id, deleted_by)
            SELECT id, :email FROM descendants""";
    public static final String FIND_DOCS_TO_DELETE = """
            SELECT
                d.id,
                d.parent_id,
                d.type,
                d.content_type,
                d.name,
                d.metadata,
                d.size,
                d.created_at,
                d.updated_at,
                d.created_by,
                d.updated_by,
                FALSE AS is_favorite
            FROM
                documents d
            JOIN
                recycle_bin r ON d.id = r.id
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM documents p
                    WHERE p.id = d.parent_id AND p.active = FALSE
                )
            ORDER BY
                r.deleted_at DESC""";

    public static final String COUNT_DOCS_TO_DELETE = """
            SELECT
                count(*)
            FROM
                documents d
            JOIN
                recycle_bin r ON d.id = r.id
            WHERE NOT EXISTS (
                    SELECT 1
                    FROM documents p
                    WHERE p.id = d.parent_id AND p.active = FALSE
                )""";

    public static final String RESTORE_DOCS = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM documents WHERE id = :docId
                UNION ALL
                SELECT d.id FROM documents d
                INNER JOIN descendants parent ON d.parent_id = parent.id
            )
            UPDATE documents
            SET active = true
            WHERE id IN (SELECT id FROM descendants)
            """;
    public static final String FIND_DESCENDANT_IDS = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM documents WHERE id = :docId
                UNION ALL
                SELECT d.id FROM documents d
                INNER JOIN descendants parent ON d.parent_id = parent.id
            )
            SELECT id FROM descendants""";

    public static final String EMPTY_BIN_WHERE_IDS = """
            WITH RECURSIVE descendants AS (
                SELECT id FROM documents WHERE id = :docId
                UNION ALL
                SELECT d.id FROM documents d
                INNER JOIN descendants parent ON d.parent_id = parent.id
            )
            delete from recycle_bin
            WHERE id IN (SELECT id FROM descendants)
            """;


    protected final DocumentRepository documentRepository;
    protected final DatabaseClient databaseClient;
    protected  final SqlUtils sqlUtils;
    
   
    public Flux<UUID> findDescendantIds(UUID documentId) {
        return databaseClient.sql(FIND_DESCENDANT_IDS)
                .bind("docId", documentId)
                .map(row -> row.get("id", UUID.class))
                .all();
    }

    public Mono<Void> softDelete(UUID documentId) {
        return getConnectedUserEmail()
                .flatMap(userEmail -> databaseClient.sql(SOFT_DELETE_DOC)
                        .bind("id", documentId)
                        .fetch()
                        .rowsUpdated()
                        .flatMap(_ -> databaseClient.sql(INSERT_INTO_RECYCLE_BIN)
                                .bind(0, documentId)
                                .bind(1, userEmail)
                                .then()));
    }

   
    public Mono<Void> softDeleteRecursive(UUID documentId, String userEmail) {

        // Operation 1: Record the deletions
        Mono<Void> recordDeletionsOperation = databaseClient.sql(RECURSIVE_INSERT_IN_RECYCLE_BIN)
                .bind("docId", documentId)
                .bind("email", userEmail)
                .then();

        // Operation 2: Perform the soft delete
        Mono<Void> softDeleteOperation = databaseClient.sql(RECURSIVE_SET_DOCS_INACTIVE)
                .bind("docId", documentId)
                .then(); // .then() is used because UPDATE doesn't typically return rows

        // Chain the operations: Execute the soft delete first, and upon its successful completion,
        // execute the operation to record the deletions.
        return recordDeletionsOperation.then(softDeleteOperation);
    }

   
    public Flux<FolderElementInfo> findDeletedDocuments() {
        return databaseClient.sql(FIND_DOCS_TO_DELETE)
                .map(mapFolderElementInfo())
                .all();
    }



    public Mono<Void> restore(UUID documentId) {
        return databaseClient.sql("select parent_id from documents where id = :docId")
                .bind("docId", documentId)
                .map(mapIdOptional())
                .one()
                .flatMap(parentId -> {
                    StringBuilder sql = new StringBuilder("UPDATE documents SET active = true");
                    return parentId.map(uuid -> databaseClient.sql("select active from documents where id = :id")
                            .bind("id", uuid)
                            .map(row -> row.get("active", Boolean.class))
                            .one()
                            .flatMap(parentActive -> {
                                if (!parentActive) {
                                    sql.append(", parent_id = null");
                                }
                                return restoreItem(documentId, sql);
                            })).orElseGet(() -> restoreItem(documentId, sql));
                });

    }

    private Mono<Void> restoreItem(UUID documentId, StringBuilder sql) {
        sql.append(" WHERE id = :docId");
        return databaseClient.sql(sql.toString())
                .bind("docId", documentId)
                .then()
                .then(databaseClient.sql("delete from recycle_bin where id = :id").bind("id", documentId).then());
    }


    public Mono<Void> restoreRecursive(UUID documentId) {
        // Recursively restore all children and the document itself
        return databaseClient.sql(RESTORE_DOCS)
                .bind("docId", documentId)
                .then()
                .then(databaseClient.sql(EMPTY_BIN_WHERE_IDS)
                        .bind("docId", documentId)
                        .then());
    }

   
    public Mono<Long> permanentDelete(UUID documentId) {
        // Find the document first to get full entity
        return databaseClient.sql("DELETE FROM documents where id = :id").bind("id", documentId).fetch().rowsUpdated();
    }

   
    public Flux<Document> findExpiredDeletedDocuments(String interval) {
        String sql = "SELECT ID FROM " + RECYCLE_BIN +
                " WHERE DELETED_AT < CURRENT_TIMESTAMP - INTERVAL '" + interval + "'";

        return databaseClient.sql(sql)
                .fetch()
                .all()
                .flatMap(row -> documentRepository.findById((UUID) row.get("id")));
    }

   
    public Mono<Long> countDeletedDocuments(String userEmail) {
        return databaseClient.sql(COUNT_DOCS_TO_DELETE)
                .map(mapCount())
                .one()
                .defaultIfEmpty(0L);
    }


    public Mono<Void> updateStatistics() {
        return databaseClient.sql("VACUUM ANALYZE documents").then();
    }

    /**
     * Calculate the total size of files that would be restored for a single document ID.
     * If the document is a folder, recursively includes all descendant files.
     *
     * @param documentId The document (file or folder) to calculate restore size for.
     * @return The total size in bytes of all files that would be restored.
     */
    public Mono<Long> getTotalSizeToRestore(UUID documentId) {
        String sql = """
                WITH RECURSIVE descendants AS (
                    SELECT id, type, size FROM documents WHERE id = :docId
                    UNION ALL
                    SELECT d.id, d.type, d.size FROM documents d
                    INNER JOIN descendants parent ON d.parent_id = parent.id
                )
                SELECT COALESCE(SUM(size), 0) as total_size FROM descendants WHERE type = 'FILE'
                """;
        return databaseClient.sql(sql)
                .bind("docId", documentId)
                .map(row -> row.get("total_size", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

}
