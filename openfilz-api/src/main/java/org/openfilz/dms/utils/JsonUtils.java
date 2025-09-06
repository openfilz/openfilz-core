package org.openfilz.dms.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import lombok.RequiredArgsConstructor;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.springframework.stereotype.Component;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class JsonUtils {

    public static final String EMPTY_JSON = "{}";

    private final ObjectMapper objectMapper;

    public Json emptyJson() {
        return Json.of(EMPTY_JSON);
    }

    public Json cloneJson(Json metadata) {
        return Json.of(metadata.asString());
    }

    public Json cloneOrNewEmptyJson(Json metadata) {
        return metadata == null ? null : cloneJson(metadata);
    }

    public Json toJson(JsonNode metadata) {
        return Json.of(metadata.toString());
    }


    public Json toJson(Map<String, Object> metadata) {
        if(metadata == null || metadata.isEmpty()) {
            return null;
        }
        try {
            return Json.of(objectMapper.writeValueAsString(metadata));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public JsonNode toJsonNode(Json json) {
        if(json ==null) {
            return null;
        }
        try {
            return objectMapper.readTree(json.asString());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String, Object> toMap(Json json) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json.asString()), Map.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public AuditLogDetails toAudiLogDetails(Json json) {
        if(json ==null) {
            return null;
        }
        try {
            return objectMapper.convertValue(objectMapper.readTree(json.asString()), AuditLogDetails.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public Json toJson(AuditLogDetails details) {
        try {
            return Json.of(objectMapper.writeValueAsString(details));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
