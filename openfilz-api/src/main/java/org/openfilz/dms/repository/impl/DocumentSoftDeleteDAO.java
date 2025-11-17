package org.openfilz.dms.repository.impl;

import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SqlColumnMapping;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.SqlQueryUtils;
import org.openfilz.dms.utils.SqlUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.openfilz.dms.entity.SqlTableMapping.RECYCLE_BIN;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.soft-delete.active", havingValue = "true")
public class DocumentSoftDeleteDAO implements UserInfoService, SqlQueryUtils {

    public static final String SOFT_DELETE_DOC = "UPDATE documents SET active = false where id = :id";
    public static final String INSERT_INTO_RECYCLE_BIN = "insert into recycle_bin(id, deleted_by) values ($1, $2)";


    protected final DocumentRepository documentRepository;
    protected final DatabaseClient databaseClient;
    protected  final SqlUtils sqlUtils;
    
   
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
        // Recursively soft delete all children and the document itself
        String sql = """
                WITH RECURSIVE descendants AS (
                    SELECT id FROM documents WHERE id = :docId
                    UNION ALL
                    SELECT d.id FROM documents d
                    INNER JOIN descendants parent ON d.parent_id = parent.id
                )
                UPDATE documents
                SET active = false
                WHERE id IN (SELECT id FROM descendants)
                """;
        String sql2 =  """
                WITH RECURSIVE descendants AS (
                    SELECT id FROM documents WHERE id = :docId
                    UNION ALL
                    SELECT d.id FROM documents d
                    INNER JOIN descendants parent ON d.parent_id = parent.id
                )
                insert into recycle_bin(id, deleted_by) values (:docId, :email)
                """;
        return databaseClient.sql(sql)
                .bind("docId", documentId)
                .fetch()
                .rowsUpdated()
                .flatMap(_-> databaseClient.sql(sql2)
                        .bind("docId", documentId)
                        .bind("email", userEmail)
                        .then())
                .then();
    }

   
    public Flux<FolderElementInfo> findDeletedDocuments(String userEmail) {
        String sql = """
                SELECT
                    d.id,
                    d.type,
                    d.content_type,
                    d.name,
                    d.metadata,
                    d.size,
                    d.created_at,
                    d.updated_at,
                    d.created_by,
                    d.updated_by,
                    FALSE as is_favorite
                FROM documents d
                    join recycle_bin r on d.id = r.id
                WHERE d.active = false
                    and r.deleted_by = :email
                ORDER BY r.deleted_at DESC
                """;

        return databaseClient.sql(sql)
                .bind("email", userEmail)
                .map(mapFolderElementInfo())
                .all();
    }

   
    public Mono<Void> restore(UUID documentId) {
        String sql = "UPDATE documents SET active = true WHERE id = :id";

        return databaseClient.sql(sql)
                .bind("id", documentId)
                .fetch()
                .rowsUpdated()
                .flatMap(_ -> databaseClient.sql("delete from recycle_bin where id = :id").bind("id", documentId).then());
    }

   
    public Mono<Void> restoreRecursive(UUID documentId) {
        // Recursively restore all children and the document itself
        String sql1 = """
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
        String sql2 = """
                WITH RECURSIVE descendants AS (
                    SELECT id FROM documents WHERE id = :docId
                    UNION ALL
                    SELECT d.id FROM documents d
                    INNER JOIN descendants parent ON d.parent_id = parent.id
                )
                delete from recycle_bin
                WHERE id IN (SELECT id FROM descendants)
                """;

        return databaseClient.sql(sql1)
                .bind("docId", documentId)
                .fetch()
                .rowsUpdated()
                .flatMap(_->databaseClient.sql(sql2)
                        .bind("docId", documentId)
                        .then());
    }

   
    public Mono<Long> permanentDelete(UUID documentId) {
        // Find the document first to get full entity
        return databaseClient.sql("DELETE FROM documents where id = :id").bind("id", documentId).fetch().rowsUpdated();
    }

   
    public Flux<Document> findExpiredDeletedDocuments(int days) {
        String sql = "SELECT ID FROM " + RECYCLE_BIN +
                " WHERE DELETED_AT < CURRENT_TIMESTAMP - INTERVAL '" + days + " days'";

        return databaseClient.sql(sql)
                .fetch()
                .all()
                .flatMap(row -> documentRepository.findById((UUID) row.get("id")));
    }

   
    public Mono<Long> countDeletedDocuments(String userEmail) {
        String sql = "SELECT COUNT(*) FROM " + RECYCLE_BIN +
                " WHERE " + SqlColumnMapping.DELETED_BY + " = :email";

        return databaseClient.sql(sql)
                .bind("email", userEmail)
                .map(row -> row.get(0, Long.class))
                .one()
                .defaultIfEmpty(0L);
    }

   
    public Mono<Void> updateStatistics() {
        return databaseClient.sql("VACUUM ANALYZE documents").then();
    }
    
}
