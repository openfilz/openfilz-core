package org.openfilz.dms.dto.signature;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureEvent;
import org.openfilz.dms.entity.SignatureField;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureEventType;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.openfilz.dms.utils.SignatureJson;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure helper methods on the e-Sign DTO records. */
class SignatureDtoHelpersTest {

    // ── SignatureFieldPlacement.toField ───────────────────────────────────

    @Test
    void placement_toField_isRequiredSignatureWithoutOptions() {
        SignatureFieldInput f = new SignatureFieldPlacement(2, 0.1, 0.2, 0.3, 0.4).toField();
        assertThat(f.type()).isEqualTo(SignatureFieldType.SIGNATURE);
        assertThat(f.page()).isEqualTo(2);
        assertThat(f.x()).isEqualTo(0.1);
        assertThat(f.y()).isEqualTo(0.2);
        assertThat(f.w()).isEqualTo(0.3);
        assertThat(f.h()).isEqualTo(0.4);
        assertThat(f.required()).isTrue();
        assertThat(f.isRequired()).isTrue();
        assertThat(f.label()).isNull();
        assertThat(f.options()).isNull();
    }

    @Test
    void fieldInput_isRequired_defaultsTrue() {
        assertThat(new SignatureFieldInput(SignatureFieldType.TEXT, 0, 0d, 0d, 1d, 1d, null, null, null).isRequired()).isTrue();
        assertThat(new SignatureFieldInput(SignatureFieldType.TEXT, 0, 0d, 0d, 1d, 1d, false, null, null).isRequired()).isFalse();
    }

    // ── SignatureRecipientInput.effective* ────────────────────────────────

    @Test
    void recipientInput_effectiveFields_prefersFieldsThenLegacyThenEmpty() {
        SignatureFieldInput text = new SignatureFieldInput(SignatureFieldType.TEXT, 0, 0d, 0d, 1d, 1d, null, null, null);
        SignatureFieldPlacement legacy = new SignatureFieldPlacement(0, 0.1, 0.1, 0.2, 0.1);

        SignatureRecipientInput both = new SignatureRecipientInput(null, null, "a@x.io", null, null, null, null, null, List.of(text), legacy);
        assertThat(both.effectiveFields()).containsExactly(text);

        SignatureRecipientInput onlyLegacy = new SignatureRecipientInput(null, null, "a@x.io", null, null, null, null, null, List.of(), legacy);
        assertThat(onlyLegacy.effectiveFields()).hasSize(1);
        assertThat(onlyLegacy.effectiveFields().getFirst().type()).isEqualTo(SignatureFieldType.SIGNATURE);

        SignatureRecipientInput none = new SignatureRecipientInput(null, null, "a@x.io", null, null, null, null, null, null, null);
        assertThat(none.effectiveFields()).isEmpty();
    }

    @Test
    void recipientInput_effectiveDefaults() {
        SignatureRecipientInput defaults = new SignatureRecipientInput(null, null, "a@x.io", null, null, null, null, null, null, null);
        assertThat(defaults.effectiveRole()).isEqualTo(SignatureRecipientRole.SIGNER);
        assertThat(defaults.effectiveAuthMethod()).isEqualTo(SignatureAuthMethod.NONE);
        assertThat(defaults.effectiveOrderIndex()).isZero();

        SignatureRecipientInput explicit = new SignatureRecipientInput(null, null, "a@x.io", 4, SignatureRecipientRole.CC, SignatureAuthMethod.SMS_OTP, null, null, null, null);
        assertThat(explicit.effectiveRole()).isEqualTo(SignatureRecipientRole.CC);
        assertThat(explicit.effectiveAuthMethod()).isEqualTo(SignatureAuthMethod.SMS_OTP);
        assertThat(explicit.effectiveOrderIndex()).isEqualTo(4);
    }

    // ── Create / Apply / Template records ────────────────────────────────

    @Test
    void createRequest_shouldSend_andIsSequential() {
        CreateSignatureEnvelopeRequest r = new CreateSignatureEnvelopeRequest(UUID.randomUUID(), "t", null, List.of(), null, null, null, null, null, null);
        assertThat(r.shouldSend()).isTrue();
        assertThat(r.isSequential()).isFalse();
        CreateSignatureEnvelopeRequest r2 = new CreateSignatureEnvelopeRequest(UUID.randomUUID(), "t", null, List.of(), null, true, null, null, false, null);
        assertThat(r2.shouldSend()).isFalse();
        assertThat(r2.isSequential()).isTrue();
    }

    @Test
    void applyRequest_isLegacy() {
        assertThat(new ApplySignatureRequest(null, "Name", null).isLegacy()).isTrue();
        assertThat(new ApplySignatureRequest(null, null, List.of()).isLegacy()).isTrue();
        assertThat(new ApplySignatureRequest(null, null, List.of(new SignatureFieldValue(UUID.randomUUID(), "v", null))).isLegacy()).isFalse();
    }

    @Test
    void templateField_toInput_copiesEverything() {
        Map<String, Object> opts = Map.of("choices", List.of("A"));
        SignatureFieldInput in = new SignatureTemplateField("Role", SignatureFieldType.SELECT, 3, 0.1, 0.2, 0.3, 0.4, false, "Pick", opts).toInput();
        assertThat(in).isEqualTo(new SignatureFieldInput(SignatureFieldType.SELECT, 3, 0.1, 0.2, 0.3, 0.4, false, "Pick", opts));
    }

    // ── from(...) mappers ────────────────────────────────────────────────

    @Test
    void fieldDto_from_withAndWithoutImage() {
        SignatureField f = SignatureField.builder()
                .id(UUID.randomUUID()).recipientId(UUID.randomUUID()).type(SignatureFieldType.SIGNATURE)
                .page(1).x(0.1).y(0.2).w(0.3).h(0.4).required(true).label("L")
                .options(SignatureJson.toJson(Map.of("k", "v")))
                .value("typed").valueImage("iVBOR...").filledAt(OffsetDateTime.now()).build();

        SignatureFieldDTO with = SignatureFieldDTO.from(f, true);
        assertThat(with.id()).isEqualTo(f.getId());
        assertThat(with.recipientId()).isEqualTo(f.getRecipientId());
        assertThat(with.type()).isEqualTo(SignatureFieldType.SIGNATURE);
        assertThat(with.page()).isEqualTo(1);
        assertThat(with.x()).isEqualTo(0.1);
        assertThat(with.h()).isEqualTo(0.4);
        assertThat(with.required()).isTrue();
        assertThat(with.label()).isEqualTo("L");
        assertThat(with.options()).containsEntry("k", "v");
        assertThat(with.value()).isEqualTo("typed");
        assertThat(with.valueImage()).isEqualTo("iVBOR...");
        assertThat(with.filledAt()).isEqualTo(f.getFilledAt());

        SignatureFieldDTO without = SignatureFieldDTO.from(f, false);
        assertThat(without.valueImage()).isNull();
        assertThat(without.value()).isEqualTo("typed");

        f.setOptions(null);
        assertThat(SignatureFieldDTO.from(f, false).options()).isNull();
    }

    @Test
    void recipientDto_from_defaultsRoleAndAuth() {
        SignatureRecipient r = SignatureRecipient.builder()
                .id(UUID.randomUUID()).recipientEmail("a@x.io").recipientName("A").orderIndex(2)
                .status(SignatureRecipientStatus.VIEWED).reminderCount(1).declineReason(null).build();
        SignatureRecipientDTO dto = SignatureRecipientDTO.from(r, List.of());
        assertThat(dto.role()).isEqualTo(SignatureRecipientRole.SIGNER);
        assertThat(dto.authMethod()).isEqualTo(SignatureAuthMethod.NONE);
        assertThat(dto.email()).isEqualTo("a@x.io");
        assertThat(dto.name()).isEqualTo("A");
        assertThat(dto.orderIndex()).isEqualTo(2);
        assertThat(dto.status()).isEqualTo(SignatureRecipientStatus.VIEWED);
        assertThat(dto.reminderCount()).isEqualTo(1);

        r.setRole(SignatureRecipientRole.CC);
        r.setAuthMethod(SignatureAuthMethod.EMAIL_OTP);
        SignatureRecipientDTO dto2 = SignatureRecipientDTO.from(r, List.of());
        assertThat(dto2.role()).isEqualTo(SignatureRecipientRole.CC);
        assertThat(dto2.authMethod()).isEqualTo(SignatureAuthMethod.EMAIL_OTP);
    }

    @Test
    void envelopeDto_andEventDto_from() {
        OffsetDateTime now = OffsetDateTime.now();
        SignatureEnvelope e = SignatureEnvelope.builder()
                .id(UUID.randomUUID()).title("T").message("M").sourceDocId(UUID.randomUUID()).signedDocId(UUID.randomUUID())
                .status(SignatureEnvelopeStatus.COMPLETED).initiatorEmail("i@x.io").sequential(true).currentOrder(2)
                .templateId(UUID.randomUUID()).reminderDays(3).sealProvider("pkcs12")
                .createdAt(now).sentAt(now).completedAt(now).expiresAt(now.plusDays(1)).build();
        SignatureEnvelopeDTO dto = SignatureEnvelopeDTO.from(e, List.of());
        assertThat(dto.id()).isEqualTo(e.getId());
        assertThat(dto.title()).isEqualTo("T");
        assertThat(dto.signedDocId()).isEqualTo(e.getSignedDocId());
        assertThat(dto.status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(dto.sequential()).isTrue();
        assertThat(dto.currentOrder()).isEqualTo(2);
        assertThat(dto.templateId()).isEqualTo(e.getTemplateId());
        assertThat(dto.reminderDays()).isEqualTo(3);
        assertThat(dto.sealProvider()).isEqualTo("pkcs12");
        assertThat(dto.expiresAt()).isEqualTo(now.plusDays(1));
        assertThat(dto.recipients()).isEmpty();

        SignatureEvent ev = SignatureEvent.builder().eventType(SignatureEventType.RECIPIENT_SIGNED).actor("a@x.io")
                .docSha256("abc").signerIp("1.2.3.4").details("d").createdAt(now).build();
        SignatureEventDTO evDto = SignatureEventDTO.from(ev);
        assertThat(evDto.type()).isEqualTo(SignatureEventType.RECIPIENT_SIGNED);
        assertThat(evDto.actor()).isEqualTo("a@x.io");
        assertThat(evDto.docSha256()).isEqualTo("abc");
        assertThat(evDto.signerIp()).isEqualTo("1.2.3.4");
        assertThat(evDto.details()).isEqualTo("d");
        assertThat(evDto.createdAt()).isEqualTo(now);
    }
}
