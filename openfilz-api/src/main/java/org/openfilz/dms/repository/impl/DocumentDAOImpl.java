package org.openfilz.dms.repository.impl;

import io.r2dbc.spi.Readable;
import jakarta.annotation.Nonnull;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.request.SearchByMetadataRequest;
import org.openfilz.dms.dto.response.AncestorInfo;
import org.openfilz.dms.dto.response.ChildElementInfo;
import org.openfilz.dms.dto.response.DocumentPosition;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SqlColumnMapping;
import org.openfilz.dms.enums.AccessType;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.repository.DocumentDAO;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.repository.SqlQueryUtils;
import org.openfilz.dms.utils.SqlUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuple2;
import reactor.util.function.Tuples;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.openfilz.dms.entity.SqlColumnMapping.*;
import static org.openfilz.dms.entity.SqlTableMapping.DOCUMENT;
import static org.openfilz.dms.enums.DocumentType.FOLDER;
import static org.openfilz.dms.utils.FileConstants.SLASH;
import static org.openfilz.dms.utils.SqlUtils.isFirst;

@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "openfilz.features.custom-access", matchIfMissing = true, havingValue = "false")
@Slf4j
public class DocumentDAOImpl implements DocumentDAO, SqlQueryUtils {

    protected static final String EQUALS_ID = " = :id";
    protected static final String IS_NULL = " is null";
    protected static final String EQUALS_TYPE = " and type = :type";

    protected static final String SELECT_ID_FROM_DOCUMENTS = "SELECT id FROM " + DOCUMENT;


    private static final String SELECT_ALL_FOLDER_ELEMENT_INFO = """
            SELECT d.id, d.type, d.name
            FROM Documents d
            WHERE d.active = true and d.parent_id""";

    protected static final String SELECT_CHILDREN = """
            WITH RECURSIVE folder_tree AS (
              SELECT
                 id,
                 name,
                 type,
                 size,
                 storage_path as storage,
                 name::text as fullpath
              FROM documents
              WHERE parent_id = :parentId AND active = true
             UNION ALL
              SELECT
                 d.id,
                 d.name,
                 d.type,
                 d.size,
                 storage_path,
                 tree.fullpath || '/' || d.name
              FROM documents d
              JOIN folder_tree tree ON d.parent_id = tree.id
              WHERE d.active = true
             )
             SELECT * FROM folder_tree""";


    protected static final String SELECT_CHILDREN_2 = """
            WITH RECURSIVE folder_tree AS (
              SELECT
                 id,
                 name,
                 type,
                 size,
                 storage_path as storage,
                 :rootFolder || name as fullpath
              FROM documents
              WHERE parent_id = :parentId AND active = true
             UNION ALL
              SELECT
                 d.id,
                 d.name,
                 d.type,
                 d.size,
                 storage_path,
                 tree.fullpath || '/' || d.name
              FROM documents d
              JOIN folder_tree tree ON d.parent_id = tree.id
              WHERE d.active = true
             )
             SELECT * FROM folder_tree""";



    protected static final String FULLPATH = "fullpath";
    protected static final String STORAGE = "storage";
    protected static final String PARENT_ID = "parentId";
    protected static final String IDS = "ids";

    protected final DocumentRepository documentRepository;

    protected final DatabaseClient databaseClient;

    protected  final SqlUtils sqlUtils;

    protected String selectDocumentIds;
    protected String selectDocumentPrefix;

    @PostConstruct
    protected void init() {
        this.selectDocumentIds =  SELECT_ID_FROM_DOCUMENTS;
        this.selectDocumentPrefix = null;
    }

    @Override
    public Flux<UUID> listDocumentIds(SearchByMetadataRequest request) {
        boolean metadataCriteria = request.metadataCriteria() != null && !request.metadataCriteria().isEmpty();
        boolean nameCriteria = request.name() != null && !request.name().isEmpty();
        boolean typeCriteria = request.type() != null;
        boolean parentFolderCriteria = request.parentFolderId() != null;
        boolean rootOnlyCriteria = request.rootOnly() != null;
        if(parentFolderCriteria && (rootOnlyCriteria && request.rootOnly())) {
            return Flux.error(new IllegalArgumentException("Impossible to specify simultaneously rootOnly 'true' and parentFolderId not null"));
        }
        if(!nameCriteria
                && !typeCriteria
                && !metadataCriteria) {
            return Flux.error(new IllegalArgumentException("All criteria cannot be empty."));
        }
        return queryDocumentIds(request, metadataCriteria, nameCriteria, typeCriteria, parentFolderCriteria, rootOnlyCriteria);
    }

    protected Flux<UUID> queryDocumentIds(SearchByMetadataRequest request, boolean metadataCriteria, boolean nameCriteria, boolean typeCriteria, boolean parentFolderCriteria, boolean rootOnlyCriteria) {
        StringBuilder sql = new StringBuilder(selectDocumentIds);
        appendMetadataFilter(sql, metadataCriteria, nameCriteria, typeCriteria, parentFolderCriteria, rootOnlyCriteria);
        DatabaseClient.GenericExecuteSpec query = databaseClient.sql(sql.toString());
        query = bindMetadataFilter(request, query, metadataCriteria, nameCriteria, typeCriteria, parentFolderCriteria);
        return executeSearchDocumentIdsQuery(query, mapId());
    }

    protected DatabaseClient.GenericExecuteSpec bindMetadataFilter(SearchByMetadataRequest request, DatabaseClient.GenericExecuteSpec query, boolean metadataCriteria, boolean nameCriteria, boolean typeCriteria, boolean parentFolderCriteria) {
        if(metadataCriteria) {
            query = sqlUtils.bindMetadata(request.metadataCriteria(), query);
        }
        if(nameCriteria) {
            query = sqlUtils.bindCriteria(NAME, request.name(), query);
        }
        if(typeCriteria) {
            query = sqlUtils.bindCriteria(TYPE, request.type().toString(), query);
        }
        if(parentFolderCriteria) {
            query = sqlUtils.bindCriteria(SqlColumnMapping.PARENT_ID, request.parentFolderId(), query);
        }
        return query;
    }

    protected void appendMetadataFilter(StringBuilder sql, boolean metadataCriteria, boolean nameCriteria, boolean typeCriteria, boolean parentFolderCriteria, boolean rootOnlyCriteria) {
        boolean first = isWhereNotInSelect();
        if(metadataCriteria) {
            first = isFirst(first, sql);
            sqlUtils.appendJsonEqualsCriteria(selectDocumentPrefix, METADATA, sql);
        }
        if(nameCriteria) {
            first = isFirst(first, sql);
            sqlUtils.appendEqualsCriteria(selectDocumentPrefix, NAME, sql);
        }
        if(typeCriteria) {
            first = isFirst(first, sql);
            sqlUtils.appendEqualsCriteria(selectDocumentPrefix, TYPE, sql);
        }
        if(parentFolderCriteria) {
            first = isFirst(first, sql);
            sqlUtils.appendEqualsCriteria(selectDocumentPrefix, SqlColumnMapping.PARENT_ID, sql);
        }
        if(rootOnlyCriteria) {
            isFirst(first, sql);
            sqlUtils.appendIsNullCriteria(selectDocumentPrefix, SqlColumnMapping.PARENT_ID, sql);
        }
    }

    protected boolean isWhereNotInSelect() {
        return true;
    }


    public Flux<ChildElementInfo> getChildren(Flux<Tuple2<UUID, String>> folderIds) {
        return folderIds.flatMap(folders -> databaseClient.sql(SELECT_CHILDREN_2)
                .bind("rootFolder", folders.getT2() + SLASH)
                .bind(PARENT_ID, folders.getT1())
                .map(this::toChild)
                .all());
    }

    @Override
    public Flux<ChildElementInfo> getChildren(UUID folderId) {
        return databaseClient.sql(SELECT_CHILDREN).bind(PARENT_ID, folderId).map(this::toChild).all();
    }

    @Override
    public Flux<ChildElementInfo> getElementsAndChildren(List<UUID> documentIds, String connectedUserEmail) {
        return databaseClient.sql("""
                SELECT
                    id,
                    name,
                    type,
                    size,
                    storage_path
                FROM documents
                where id in (:ids) AND active = true""")
                .bind(IDS, documentIds)
                .map(this::toRootChild)
                .all()
                .mergeWith(getChildren(getFolders(documentIds)));
    }

    @Override
    public Flux<FolderElementInfo> listDocumentInfoInFolder(UUID parentFolderId, DocumentType type) {
        StringBuilder sql = buildListDocumentInfoInFolderQuery(parentFolderId, type);
        DatabaseClient.GenericExecuteSpec query = databaseClient.sql(sql.toString());
        if(parentFolderId != null) {
            query = query.bind(ID, parentFolderId);
        }
        if(type != null) {
            query = query.bind(TYPE, type.toString());
        }
        return executeListDocumentInfoQuery(query, mapFolderElementInfo());
    }

    protected  <T> Flux<T> executeListDocumentInfoQuery(DatabaseClient.GenericExecuteSpec query, Function<Readable, T> mappingFunction) {
        return query.map(mappingFunction).all();
    }

    protected  <T> Flux<T> executeSearchDocumentIdsQuery(DatabaseClient.GenericExecuteSpec query, Function<Readable, T> mappingFunction) {
       return query.map(mappingFunction).all();
    }

    protected StringBuilder buildListDocumentInfoInFolderQuery(UUID parentFolderId, DocumentType type) {
        StringBuilder sql = new StringBuilder();
        sql.append(SELECT_ALL_FOLDER_ELEMENT_INFO);

        if(parentFolderId != null) {
            sql.append(EQUALS_ID);
        } else {
            sql.append(IS_NULL);
        }

        if(type != null) {
            sql.append(EQUALS_TYPE);
        }
        return sql;
    }

    @Override
    public Mono<Long> countDocument(UUID parentId) {
        return parentId == null ? documentRepository.countDocumentByParentIdIsNullAndActiveIsTrue()
                : documentRepository.countDocumentByParentIdEqualsAndActiveIsTrue(parentId);
    }

    protected Flux<Tuple2<UUID, String>> getFolders(List<UUID> documentIds) {
        return databaseClient.sql("select id, name from documents where type = :type and id in (:ids) AND active = true")
                .bind(TYPE, DocumentType.FOLDER.toString())
                .bind(IDS, documentIds)
                .map(row -> Tuples.of(row.get(ID, UUID.class), row.get(NAME, String.class)))
                .all();
    }

    protected ChildElementInfo toRootChild(io.r2dbc.spi.Readable row) {
        return getChildElementInfo(row, NAME, STORAGE_PATH);

    }

    protected ChildElementInfo toChild(io.r2dbc.spi.Readable row) {
        return getChildElementInfo(row, FULLPATH, STORAGE);
    }

    protected ChildElementInfo getChildElementInfo(Readable row, String fullpath, String storage) {
        return ChildElementInfo.builder()
                .id(row.get(ID, UUID.class))
                .name(row.get(NAME, String.class))
                .path(row.get(fullpath, String.class))
                .storagePath(row.get(storage, String.class))
                .type(DocumentType.valueOf(row.get(TYPE, String.class)))
                .size(row.get(SIZE, Long.class))
                .build();
    }

    @Override
    public Mono<Boolean> existsByNameAndParentId(String name, UUID parentId) {
        return parentId == null ? documentRepository.existsByNameAndParentIdIsNullAndActiveIsTrue(name) : documentRepository.existsByNameAndParentIdAndActiveIsTrue(name, parentId);
    }

    @Override
    public Mono<Boolean> existsByIdAndType(UUID id, DocumentType type, AccessType accessType) {
        return documentRepository.existsByIdAndTypeAndActive(id, type, true);
    }

    @Override
    public Mono<Document> getFolderToDelete(UUID folderId) {
        return documentRepository.findByIdAndType(folderId, FOLDER);
    }

    @Override
    public Flux<Document> findDocumentsByParentIdAndType(@Nonnull UUID folderId, @Nonnull DocumentType documentType) {
        return documentRepository.findByParentIdAndType(folderId, documentType);
    }

    @Override
    public Mono<Document> findById(UUID documentId, AccessType accessType) {
        return documentRepository.findByIdAndActive(documentId, true);
    }

    @Override
    public Mono<Document> update(Document document) {
        return documentRepository.save(document);
    }

    @Override
    public Mono<Document> create(Document document) {
        return documentRepository.save(document);
    }

    @Override
    public Mono<Void> delete(Document document) {
        return documentRepository.delete(document);
    }

    @Override
    public Flux<AncestorInfo> getAncestors(UUID documentId) {
        String sql = """
            WITH RECURSIVE ancestors AS (
                SELECT id, name, type, parent_id, 1 as depth
                FROM documents
                WHERE id = (SELECT parent_id FROM documents WHERE id = :documentId AND active = true)
                  AND active = true
                UNION ALL
                SELECT d.id, d.name, d.type, d.parent_id, a.depth + 1
                FROM documents d
                JOIN ancestors a ON d.id = a.parent_id
                WHERE d.active = true
            )
            SELECT id, name, type FROM ancestors
            ORDER BY depth DESC
            """;
        return databaseClient.sql(sql)
                .bind("documentId", documentId)
                .map(row -> new AncestorInfo(
                        row.get(ID, UUID.class),
                        row.get(NAME, String.class),
                        row.get(TYPE, String.class)
                ))
                .all();
    }

    @Override
    public Mono<DocumentPosition> getDocumentPosition(UUID documentId, String sortBy, String sortOrder) {
        // Validate and sanitize sort parameters to prevent SQL injection
        String safeSortBy = switch (sortBy != null ? sortBy.toLowerCase() : "name") {
            case "name" -> "name";
            case "updated_at", "updatedat" -> "updated_at";
            case "created_at", "createdat" -> "created_at";
            case "size" -> "size";
            case "type" -> "type";
            default -> "name";
        };
        String safeSortOrder = "DESC".equalsIgnoreCase(sortOrder) ? "DESC" : "ASC";

        String sql = String.format("""
            WITH target_doc AS (
                SELECT id, parent_id FROM documents WHERE id = :documentId AND active = true
            ),
            folder_items AS (
                SELECT
                    id,
                    ROW_NUMBER() OVER (
                        ORDER BY
                            CASE WHEN type = 'FOLDER' THEN 0 ELSE 1 END,
                            %s %s
                    ) - 1 as position
                FROM documents
                WHERE (parent_id = (SELECT parent_id FROM target_doc) OR (parent_id IS NULL AND (SELECT parent_id FROM target_doc) IS NULL))
                  AND active = true
            )
            SELECT
                :documentId as document_id,
                (SELECT parent_id FROM target_doc) as parent_id,
                (SELECT position FROM folder_items WHERE id = :documentId) as position,
                (SELECT COUNT(*) FROM folder_items) as total_items
            """, safeSortBy, safeSortOrder);

        return databaseClient.sql(sql)
                .bind("documentId", documentId)
                .map(row -> new DocumentPosition(
                        row.get("document_id", UUID.class),
                        row.get("parent_id", UUID.class),
                        row.get("position", Long.class),
                        row.get("total_items", Long.class)
                ))
                .one();
    }

    @Override
    public Mono<Long> getTotalStorageByUser(String username) {
        // Optimized query: sum only FILE sizes (folders have no size), filter by created_by and active
        // Uses COALESCE to return 0 if no files exist for the user
        String sql = """
            SELECT COALESCE(SUM(size), 0) as total_size
            FROM documents
            WHERE created_by = :username
              AND type = 'FILE'
              AND active = true
            """;
        return databaseClient.sql(sql)
                .bind("username", username)
                .map(row -> row.get("total_size", Long.class))
                .one()
                .defaultIfEmpty(0L);
    }
}
