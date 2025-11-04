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

    private final ObjectMapper objectMapper;

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
        return sneakyToJson(metadata);
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
        return sneakyConvertValue(json.asString(), Map.class);
    }

    public AuditLogDetails toAudiLogDetails(Json json) {
        if(json ==null) {
            return null;
        }
        return sneakyConvertValue(json.asString(), AuditLogDetails.class);
    }

    public Json toJson(AuditLogDetails details) {
        return sneakyToJson(details);
    }

    private <T> T sneakyConvertValue(String json, Class<T> clazz) {
        try {
            return objectMapper.convertValue(objectMapper.readTree(json), clazz);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    private Json sneakyToJson(Object object) {
        try {
            return Json.of(objectMapper.writeValueAsString(object));
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }
}
