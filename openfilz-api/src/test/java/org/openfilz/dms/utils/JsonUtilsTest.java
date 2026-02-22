package org.openfilz.dms.utils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.audit.AuditLogDetails;
import org.openfilz.dms.dto.audit.UploadAudit;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JsonUtilsTest {

    private JsonUtils jsonUtils;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        jsonUtils = new JsonUtils(objectMapper);
    }

    @Test
    void toJson_fromMap_returnsJson() {
        Map<String, Object> map = Map.of("key1", "value1", "key2", 42);

        Json result = jsonUtils.toJson(map);

        assertNotNull(result);
        String jsonStr = result.asString();
        assertTrue(jsonStr.contains("key1"));
        assertTrue(jsonStr.contains("value1"));
    }

    @Test
    void toJson_fromNullMap_returnsNull() {
        Json result = jsonUtils.toJson((Map<String, Object>) null);
        assertNull(result);
    }

    @Test
    void toJson_fromEmptyMap_returnsNull() {
        Json result = jsonUtils.toJson(Map.of());
        assertNull(result);
    }

    @Test
    void toMap_convertsJsonToMap() {
        Json json = Json.of("{\"key1\":\"value1\",\"key2\":42}");

        Map<String, Object> result = jsonUtils.toMap(json);

        assertEquals("value1", result.get("key1"));
        assertEquals(42, result.get("key2"));
    }

    @Test
    void cloneJson_returnsNewInstanceWithSameContent() {
        Json original = Json.of("{\"key\":\"value\"}");

        Json clone = jsonUtils.cloneJson(original);

        assertNotNull(clone);
        assertEquals(original.asString(), clone.asString());
    }

    @Test
    void cloneOrNewEmptyJson_withNull_returnsNull() {
        Json result = jsonUtils.cloneOrNewEmptyJson(null);
        assertNull(result);
    }

    @Test
    void cloneOrNewEmptyJson_withJson_returnsClone() {
        Json original = Json.of("{\"key\":\"value\"}");

        Json result = jsonUtils.cloneOrNewEmptyJson(original);

        assertNotNull(result);
        assertEquals(original.asString(), result.asString());
    }

    @Test
    void toJsonNode_fromJson_returnsJsonNode() {
        Json json = Json.of("{\"name\":\"test\"}");

        JsonNode result = jsonUtils.toJsonNode(json);

        assertNotNull(result);
        assertEquals("test", result.get("name").asText());
    }

    @Test
    void toJsonNode_fromNull_returnsNull() {
        JsonNode result = jsonUtils.toJsonNode(null);
        assertNull(result);
    }

    @Test
    void toJson_fromJsonNode_returnsJson() {
        JsonNode node = objectMapper.createObjectNode()
                .put("key", "value");

        Json result = jsonUtils.toJson(node);

        assertNotNull(result);
        assertTrue(result.asString().contains("key"));
    }

    @Test
    void roundTrip_mapToJsonAndBack() {
        Map<String, Object> original = Map.of("name", "test", "count", 5);

        Json json = jsonUtils.toJson(original);
        Map<String, Object> back = jsonUtils.toMap(json);

        assertEquals("test", back.get("name"));
        assertEquals(5, back.get("count"));
    }

    @Test
    void toAuditLogDetails_withNull_returnsNull() {
        assertNull(jsonUtils.toAudiLogDetails(null));
    }

    @Test
    void toAuditLogDetails_withValidJson_returnsDetails() {
        UUID parentId = UUID.randomUUID();
        String jsonStr = "{\"type\":\"upload\",\"filename\":\"test.txt\",\"parentFolderId\":\"" + parentId + "\"}";
        Json json = Json.of(jsonStr);

        AuditLogDetails result = jsonUtils.toAudiLogDetails(json);

        assertNotNull(result);
        assertInstanceOf(UploadAudit.class, result);
        UploadAudit upload = (UploadAudit) result;
        assertEquals("test.txt", upload.getFilename());
    }

    @Test
    void toJson_fromAuditLogDetails_returnsJson() {
        UploadAudit audit = new UploadAudit("file.pdf", UUID.randomUUID(), Map.of("key", "value"));

        Json result = jsonUtils.toJson(audit);

        assertNotNull(result);
        String jsonStr = result.asString();
        assertTrue(jsonStr.contains("file.pdf"));
        assertTrue(jsonStr.contains("upload")); // type discriminator
    }

    @Test
    void toJsonNode_withInvalidJson_throwsRuntimeException() {
        Json invalidJson = Json.of("not valid json {{{");
        assertThrows(RuntimeException.class, () -> jsonUtils.toJsonNode(invalidJson));
    }

    @Test
    void toAudiLogDetails_withInvalidJson_throwsRuntimeException() {
        Json invalidJson = Json.of("not valid json");
        assertThrows(RuntimeException.class, () -> jsonUtils.toAudiLogDetails(invalidJson));
    }

    @Test
    void toMap_withValidJson_returnsCorrectTypes() {
        Json json = Json.of("{\"str\":\"hello\",\"num\":42,\"bool\":true}");
        Map<String, Object> result = jsonUtils.toMap(json);
        assertEquals("hello", result.get("str"));
        assertEquals(42, result.get("num"));
        assertEquals(true, result.get("bool"));
    }
}
