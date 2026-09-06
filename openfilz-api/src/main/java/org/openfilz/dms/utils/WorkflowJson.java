package org.openfilz.dms.utils;

import io.r2dbc.postgresql.codec.Json;
import org.openfilz.dms.dto.workflow.WorkflowSpec;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Static JSON helpers for the workflow JSONB columns ({@code spec}, {@code trigger_folder_ids},
 * {@code assignments}, {@code details}). Private immutable mapper, no Spring context needed.
 */
public final class WorkflowJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WorkflowJson() {}

    public static Json toJson(Object value) {
        if (value == null) return null;
        return Json.of(MAPPER.writeValueAsString(value));
    }

    public static String stringify(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }

    public static WorkflowSpec toSpec(Json json) {
        if (json == null) return new WorkflowSpec(List.of());
        return MAPPER.readValue(json.asString(), WorkflowSpec.class);
    }

    public static WorkflowSpec parseSpec(String json) {
        return MAPPER.readValue(json, WorkflowSpec.class);
    }

    public static List<UUID> toUuidList(Json json) {
        if (json == null) return List.of();
        List<String> raw = MAPPER.readValue(json.asString(), new TypeReference<List<String>>() {});
        return raw.stream().map(UUID::fromString).toList();
    }

    public static Map<String, List<String>> toAssignments(Json json) {
        if (json == null) return Map.of();
        return MAPPER.readValue(json.asString(), new TypeReference<Map<String, List<String>>>() {});
    }

    public static Map<String, Object> toMap(Json json) {
        if (json == null) return null;
        return MAPPER.readValue(json.asString(), new TypeReference<Map<String, Object>>() {});
    }
}
