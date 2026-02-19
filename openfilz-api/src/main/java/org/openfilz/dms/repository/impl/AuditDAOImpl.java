package org.openfilz.dms.repository.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.config.AuditChainProperties;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.IAuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.AuditDAO;
import org.openfilz.dms.service.AuditChainService;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.openfilz.dms.utils.SqlUtils.isFirst;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditDAOImpl implements AuditDAO, UserInfoService {

    private final DatabaseClient databaseClient;
    private final ObjectMapper objectMapper;
    private final JsonUtils jsonUtils;
    private final AuditChainProperties chainProperties;
    private final AuditChainService auditChainService;
    private final TransactionalOperator tx;

    @Override
    public Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort) {
        String sql = "SELECT timestamp, user_principal, action, resource_type, details, previous_hash, hash FROM audit_logs WHERE resource_id = :resourceId ORDER BY timestamp " + sort.toString();
        return databaseClient.sql(sql)
                .bind("resourceId", resourceId)
                .map(row -> {
                    String type = row.get("resource_type", String.class);
                    return new AuditLog(
                            null,
                            row.get("timestamp", OffsetDateTime.class),
                            row.get("user_principal", String.class),
                            AuditAction.valueOf(row.get("action", String.class)),
                            type != null ? DocumentType.valueOf(type) : null,
                            jsonUtils.toAudiLogDetails(row.get("details", Json.class)),
                            row.get("previous_hash", String.class),
                            row.get("hash", String.class)
                    );
                }).all();
    }

    @Override
    public Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request) {
        StringBuilder sql = new StringBuilder("SELECT resource_id, timestamp, user_principal, action, resource_type, details, previous_hash, hash FROM audit_logs");
        boolean first = true;
        boolean idCriteria = request.id() != null;
        if(idCriteria) {
            first = isFirst(first, sql);
            sql.append("resource_id = :id ");
        }
        boolean actionCriteria = request.action() != null;
        if(actionCriteria) {
            first = isFirst(first, sql);
            sql.append("action = :action ");
        }
        boolean typeCriteria = request.type() != null;
        if(typeCriteria) {
            first = isFirst(first, sql);
            sql.append("resource_type = :type ");
        }
        boolean usernameCriteria = request.username() != null && !request.username().isEmpty();
        if(usernameCriteria) {
            first = isFirst(first, sql);
            sql.append("user_principal = :username ");
        }
        boolean detailsCriteria = request.details() != null && !request.details().isEmpty();
        if(detailsCriteria) {
            isFirst(first, sql);
            sql.append("details @> :details::jsonb ");
        }
        DatabaseClient.GenericExecuteSpec query = databaseClient.sql(sql.toString());
        if(idCriteria) {
            query = query.bind("id", request.id());
        }
        if(actionCriteria) {
            query = query.bind("action", request.action().toString());
        }
        if(typeCriteria) {
            query = query.bind("type", request.type().toString());
        }
        if(usernameCriteria) {
            query = query.bind("username", request.username());
        }
        if(detailsCriteria) {
            try {
                String criteriaJson = objectMapper.writeValueAsString(request.details());
                query = query.bind("details", criteriaJson);
            } catch (JsonProcessingException e) {
                throw new RuntimeException(e);
            }
        }

        return query.map(row -> {
                    String type = row.get("resource_type", String.class);
                    return new AuditLog(
                        row.get("resource_id", UUID.class),
                        row.get("timestamp", OffsetDateTime.class),
                        row.get("user_principal", String.class),
                        AuditAction.valueOf(row.get("action", String.class)),
                        type != null ? DocumentType.valueOf(type) : null,
                        jsonUtils.toAudiLogDetails(row.get("details", Json.class)),
                        row.get("previous_hash", String.class),
                        row.get("hash", String.class));
                })
                .all();
    }

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details) {
        if (!chainProperties.isEnabled()) {
            return logActionWithoutChain(action, resourceType, resourceId, details);
        }
        return logActionWithChain(action, resourceType, resourceId, details);
    }

    private Mono<Void> logActionWithoutChain(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details) {
        StringBuilder sql = new StringBuilder("INSERT INTO audit_logs (timestamp, user_principal, action, resource_type, resource_id");
        if (details != null) {
            sql.append(", details) VALUES (:ts, :up, :act, :rt, :rid, :det)");
        } else {
            sql.append(") VALUES (:ts, :up, :act, :rt, :rid)");
        }
        return getConnectedUserEmail()
                .flatMap(username -> {
                    DatabaseClient.GenericExecuteSpec executeSpec = databaseClient.sql(sql.toString())
                            .bind("ts", OffsetDateTime.now())
                            .bind("up", username != null ? username : "SYSTEM")
                            .bind("act", action.toString());
                    if (details != null) {
                        executeSpec = executeSpec.bind("det", jsonUtils.toJson(details));
                    }
                    if(resourceType != null) {
                        executeSpec = executeSpec.bind("rt", resourceType.toString());
                    } else {
                        executeSpec = executeSpec.bindNull("rt", String.class);
                    }
                    if(resourceId != null) {
                        executeSpec = executeSpec.bind("rid", resourceId);
                    } else {
                        executeSpec = executeSpec.bindNull("rid", UUID.class);
                    }
                    return executeSpec.then();
                })
                .doOnError(e -> log.error("Failed to log audit action {}: {}", action, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> logActionWithChain(AuditAction action, DocumentType resourceType, UUID resourceId, IAuditLogDetails details) {
        return getConnectedUserEmail()
                .flatMap(username -> {
                    String userPrincipal = username != null ? username : "SYSTEM";
                    OffsetDateTime timestamp = OffsetDateTime.now();

                    // Acquire advisory lock, read last hash, compute new hash, insert — all in one transaction
                    Mono<Void> chainedInsert = databaseClient.sql("SELECT pg_advisory_xact_lock(1)")
                            .then()
                            .then(getLastHash()
                                    .defaultIfEmpty(auditChainService.computeGenesisHash())
                            )
                            .flatMap(previousHash -> {
                                String newHash = auditChainService.computeHash(
                                        timestamp, userPrincipal, action, resourceType, resourceId, details, previousHash);

                                StringBuilder sql = new StringBuilder(
                                        "INSERT INTO audit_logs (timestamp, user_principal, action, resource_type, resource_id, previous_hash, hash");
                                if (details != null) {
                                    sql.append(", details) VALUES (:ts, :up, :act, :rt, :rid, :ph, :h, :det)");
                                } else {
                                    sql.append(") VALUES (:ts, :up, :act, :rt, :rid, :ph, :h)");
                                }

                                DatabaseClient.GenericExecuteSpec executeSpec = databaseClient.sql(sql.toString())
                                        .bind("ts", timestamp)
                                        .bind("up", userPrincipal)
                                        .bind("act", action.toString())
                                        .bind("ph", previousHash)
                                        .bind("h", newHash);

                                if (details != null) {
                                    executeSpec = executeSpec.bind("det", jsonUtils.toJson(details));
                                }
                                if (resourceType != null) {
                                    executeSpec = executeSpec.bind("rt", resourceType.toString());
                                } else {
                                    executeSpec = executeSpec.bindNull("rt", String.class);
                                }
                                if (resourceId != null) {
                                    executeSpec = executeSpec.bind("rid", resourceId);
                                } else {
                                    executeSpec = executeSpec.bindNull("rid", UUID.class);
                                }
                                return executeSpec.then();
                            });

                    return chainedInsert.as(tx::transactional);
                })
                .doOnError(e -> log.error("Failed to log chained audit action {}: {}", action, e.getMessage()))
                .onErrorResume(e -> Mono.empty());
    }

    @Override
    public Mono<String> getLastHash() {
        return databaseClient.sql("SELECT hash FROM audit_logs WHERE hash IS NOT NULL ORDER BY id DESC LIMIT 1")
                .map(row -> row.get("hash", String.class))
                .one();
    }

    @Override
    public Mono<Boolean> isChainInitialized() {
        return databaseClient.sql("SELECT EXISTS(SELECT 1 FROM audit_logs WHERE action = 'CHAIN_GENESIS')")
                .map(row -> row.get(0, Boolean.class))
                .one()
                .defaultIfEmpty(false);
    }

    @Override
    public Flux<AuditLog> getChainedEntries() {
        return databaseClient.sql("SELECT id, timestamp, user_principal, action, resource_type, resource_id, details, previous_hash, hash FROM audit_logs WHERE hash IS NOT NULL ORDER BY id ASC")
                .map(this::mapChainedRow)
                .all();
    }

    @Override
    public Flux<AuditLog> getChainedEntriesInRange(long fromId, long toId) {
        return databaseClient.sql("SELECT id, timestamp, user_principal, action, resource_type, resource_id, details, previous_hash, hash FROM audit_logs WHERE hash IS NOT NULL AND id >= :fromId AND id <= :toId ORDER BY id ASC")
                .bind("fromId", fromId)
                .bind("toId", toId)
                .map(this::mapChainedRow)
                .all();
    }

    private AuditLog mapChainedRow(io.r2dbc.spi.Readable row) {
        String type = row.get("resource_type", String.class);
        UUID resourceId = row.get("resource_id", UUID.class);
        return new AuditLog(
                resourceId,
                row.get("timestamp", OffsetDateTime.class),
                row.get("user_principal", String.class),
                AuditAction.valueOf(row.get("action", String.class)),
                type != null ? DocumentType.valueOf(type) : null,
                jsonUtils.toAudiLogDetails(row.get("details", Json.class)),
                row.get("previous_hash", String.class),
                row.get("hash", String.class)
        );
    }
}
