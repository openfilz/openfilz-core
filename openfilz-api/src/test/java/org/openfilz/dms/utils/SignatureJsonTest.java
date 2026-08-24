package org.openfilz.dms.utils;

import io.r2dbc.postgresql.codec.Json;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureRecipientRole;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignatureJsonTest {

    @Test
    void toJson_null_returnsNull() {
        assertThat(SignatureJson.toJson(null)).isNull();
    }

    @Test
    void toJson_andToMap_roundTrip() {
        Json json = SignatureJson.toJson(Map.of("choices", List.of("A", "B"), "group", "g1", "n", 2));
        Map<String, Object> back = SignatureJson.toMap(json);
        assertThat(back).containsEntry("choices", List.of("A", "B")).containsEntry("group", "g1").containsEntry("n", 2);
    }

    @Test
    void toMap_null_returnsNull() {
        assertThat(SignatureJson.toMap(null)).isNull();
    }

    @Test
    void toList_null_returnsEmptyList() {
        assertThat(SignatureJson.toList(null, SignatureTemplateRole.class)).isEmpty();
    }

    @Test
    void toList_deserializesRecords() {
        List<SignatureTemplateRole> roles = List.of(
                new SignatureTemplateRole("Client", 1, SignatureRecipientRole.SIGNER, SignatureAuthMethod.EMAIL_OTP),
                new SignatureTemplateRole("CC", null, SignatureRecipientRole.CC, null));
        List<SignatureTemplateRole> back = SignatureJson.toList(SignatureJson.toJson(roles), SignatureTemplateRole.class);
        assertThat(back).containsExactlyElementsOf(roles);
    }

    @Test
    void toList_wrongShape_throws() {
        assertThatThrownBy(() -> SignatureJson.toList(Json.of("{\"not\":\"a list\"}"), SignatureTemplateRole.class))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void stringify() {
        assertThat(SignatureJson.stringify(null)).isNull();
        assertThat(SignatureJson.stringify(Map.of("a", 1))).isEqualTo("{\"a\":1}");
        assertThat(SignatureJson.stringify(List.of("x"))).isEqualTo("[\"x\"]");
    }
}
