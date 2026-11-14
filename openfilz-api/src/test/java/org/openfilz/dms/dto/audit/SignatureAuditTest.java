package org.openfilz.dms.dto.audit;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.utils.JsonUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The JSON a {@link SignatureAudit} becomes: what the trail stores and reads back, and what a consumer of the details sees. */
class SignatureAuditTest {

    private final ObjectMapper mapper = new JsonMapper();
    private final JsonUtils jsonUtils = new JsonUtils(mapper);

    private final UUID envelopeId = UUID.randomUUID();
    private final UUID sourceDocId = UUID.randomUUID();

    private SignatureEnvelope envelope(SignatureEnvelopeStatus status) {
        return SignatureEnvelope.builder().id(envelopeId).title("Quote 42").sourceDocId(sourceDocId).status(status).build();
    }

    @Test
    void storedDetails_readBackAsTheSameSubtype_andOmitWhatIsNotKnownYet() {
        Json stored = jsonUtils.toJson(SignatureAudit.of(envelope(SignatureEnvelopeStatus.SENT)));

        JsonNode json = mapper.readTree(stored.asString());
        assertThat(json.get("type").asString()).isEqualTo("signature");
        assertThat(json.get("envelopeId").asString()).isEqualTo(envelopeId.toString());
        assertThat(json.get("status").asString()).isEqualTo("SENT");
        assertThat(json.has("signedDocumentId")).isFalse();   // not sealed yet

        AuditLogDetails read = jsonUtils.toAudiLogDetails(stored);
        assertThat(read).isInstanceOf(SignatureAudit.class);
        SignatureAudit details = (SignatureAudit) read;
        assertThat(details.getEnvelopeId()).isEqualTo(envelopeId);
        assertThat(details.getEnvelopeTitle()).isEqualTo("Quote 42");
        assertThat(details.getSourceDocumentId()).isEqualTo(sourceDocId);
        assertThat(details.getSignedDocumentId()).isNull();
        // Reading an entry back must serialise to the very same JSON: the hash chain is recomputed from it.
        assertThat(mapper.writeValueAsString(read)).isEqualTo(mapper.writeValueAsString(SignatureAudit.of(envelope(SignatureEnvelopeStatus.SENT))));
    }

    @Test
    void completed_namesTheSealedDocument() {
        UUID signedDocId = UUID.randomUUID();

        SignatureAudit details = SignatureAudit.completed(envelope(SignatureEnvelopeStatus.SENT), signedDocId);

        assertThat(details.getSignedDocumentId()).isEqualTo(signedDocId);
        assertThat(details.getSourceDocumentId()).isEqualTo(sourceDocId);
        assertThat(details.getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void asAMapValue_theEnvelopeIdIsThere_butTheTypeDiscriminatorIsNot() {
        // Details embedded as an untyped value (a Map<String, Object> entry) are written by their
        // runtime class only: Jackson adds the "type" property when it knows the declared base type.
        // A reader of such a payload must rely on the fields, not on "type".
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("details", SignatureAudit.of(envelope(SignatureEnvelopeStatus.SENT)));

        JsonNode details = mapper.readTree(mapper.writeValueAsString(payload)).get("details");

        assertThat(details.get("envelopeId").asString()).isEqualTo(envelopeId.toString());
        assertThat(details.has("type")).isFalse();
    }
}
