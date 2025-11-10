package org.openfilz.dms.repository.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.openfilz.dms.repository.AuditDAO;
import org.openfilz.dms.utils.JsonUtils;
import org.openfilz.dms.utils.UserInfoService;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.openfilz.dms.utils.SqlUtils.isFirst;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditDAOImpl implements AuditDAO, UserInfoService {

    private static final String AUDIT_VALUES_SQL = ") VALUES (:ts, :up, :act, :rt, :rid";
    private static final String AUDIT_INSERT_SQL = "INSERT INTO audit_logs (timestamp, user_principal, action, resource_type, resource_id";

    private final DatabaseClient databaseClient;

    private  final ObjectMapper objectMapper;

    private final JsonUtils jsonUtils;

    @Override
    public Flux<AuditLog> getAuditTrail(UUID resourceId, SortOrder sort) {
        String sql = "SELECT timestamp, user_principal, action, resource_type, details FROM audit_logs WHERE resource_id = :resourceId ORDER BY timestamp " + sort.toString();
        return databaseClient.sql(sql)
                .bind("resourceId", resourceId)
                .map(row -> new AuditLog(
                        null,
                        row.get("timestamp", OffsetDateTime.class),
                        row.get("user_principal", String.class),
                        AuditAction.valueOf(row.get("action", String.class)),
                        DocumentType.valueOf(row.get("resource_type", String.class)),
                        jsonUtils.toAudiLogDetails(row.get("details", Json.class))
                )).all();
    }

    @Override
    public Flux<AuditLog> searchAuditTrail(SearchByAuditLogRequest request) {
        StringBuilder sql = new StringBuilder("SELECT resource_id, timestamp, user_principal, action, resource_type, details FROM audit_logs");
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

        return query.map(row -> new AuditLog(
                        row.get("resource_id", UUID.class),
                        row.get("timestamp", OffsetDateTime.class),
                        row.get("user_principal", String.class),
                        AuditAction.valueOf(row.get("action", String.class)),
                        DocumentType.valueOf(row.get("resource_type", String.class)),
                        jsonUtils.toAudiLogDetails(row.get("details", Json.class))))
                .all();
    }

    @Override
    public Mono<Void> logAction(AuditAction action, DocumentType resourceType, UUID resourceId, AuditLogDetails details) {
        StringBuilder sql = new StringBuilder(AUDIT_INSERT_SQL);
        if (details != null) {
            sql.append(", details ").append(AUDIT_VALUES_SQL).append(", :det)");
        } else {
            sql.append(AUDIT_VALUES_SQL).append(")");
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

}
