package org.openfilz.dms.utils;

import io.r2dbc.postgresql.codec.Json;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * Static JSON helpers for the e-Sign JSONB columns ({@code signature_field.options},
 * {@code signature_template.roles/fields}). Uses a private immutable mapper so it can be
 * called from record factories without a Spring context.
 */
public final class SignatureJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private SignatureJson() {}

    public static Json toJson(Object value) {
        if (value == null) return null;
        return Json.of(MAPPER.writeValueAsString(value));
    }

    public static Map<String, Object> toMap(Json json) {
        if (json == null) return null;
        return MAPPER.readValue(json.asString(), new TypeReference<Map<String, Object>>() {});
    }

    public static <T> List<T> toList(Json json, Class<T> elementType) {
        if (json == null) return List.of();
        return MAPPER.readValue(json.asString(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, elementType));
    }

    public static String stringify(Object value) {
        return value == null ? null : MAPPER.writeValueAsString(value);
    }
}
