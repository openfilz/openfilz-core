package org.openfilz.dms.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.enums.SignatureRecipientStatus;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Helper predicates on the e-Sign entities and enums. */
class SignatureEntityHelpersTest {

    // ── SignatureFieldType ────────────────────────────────────────────────

    @Test
    void fieldType_isImage_andIsAuto() {
        assertThat(SignatureFieldType.values()).filteredOn(SignatureFieldType::isImage)
                .containsExactlyInAnyOrder(SignatureFieldType.SIGNATURE, SignatureFieldType.INITIALS,
                        SignatureFieldType.IMAGE, SignatureFieldType.STAMP);
        assertThat(SignatureFieldType.values()).filteredOn(SignatureFieldType::isAuto)
                .containsExactly(SignatureFieldType.DATE_SIGNED);
        assertThat(SignatureFieldType.values()).hasSize(12);
    }

    // ── SignatureEnvelopeStatus ───────────────────────────────────────────

    @Test
    void envelopeStatus_isTerminal() {
        assertThat(SignatureEnvelopeStatus.values()).filteredOn(SignatureEnvelopeStatus::isTerminal)
                .containsExactlyInAnyOrder(SignatureEnvelopeStatus.COMPLETED, SignatureEnvelopeStatus.DECLINED,
                        SignatureEnvelopeStatus.CANCELLED, SignatureEnvelopeStatus.EXPIRED);
        assertThat(SignatureEnvelopeStatus.DRAFT.isTerminal()).isFalse();
        assertThat(SignatureEnvelopeStatus.SENT.isTerminal()).isFalse();
    }

    // ── SignatureField.isFilled ───────────────────────────────────────────

    @ParameterizedTest
    @EnumSource(SignatureFieldType.class)
    void field_isFilled_dependsOnTypeKind(SignatureFieldType type) {
        SignatureField valueOnly = SignatureField.builder().type(type).value("v").build();
        SignatureField imageOnly = SignatureField.builder().type(type).valueImage("aW1n").build();
        SignatureField blanks = SignatureField.builder().type(type).value("  ").valueImage(" ").build();
        SignatureField empty = SignatureField.builder().type(type).build();
        if (type.isImage()) {
            assertThat(valueOnly.isFilled()).as("%s typed-only", type).isFalse();
            assertThat(imageOnly.isFilled()).as("%s image", type).isTrue();
        } else {
            assertThat(valueOnly.isFilled()).as("%s value", type).isTrue();
            assertThat(imageOnly.isFilled()).as("%s image-only", type).isFalse();
        }
        assertThat(blanks.isFilled()).isFalse();
        assertThat(empty.isFilled()).isFalse();
    }

    @Test
    void field_isFilled_nullType_usesValue() {
        assertThat(SignatureField.builder().value("x").build().isFilled()).isTrue();
        assertThat(SignatureField.builder().valueImage("x").build().isFilled()).isFalse();
    }

    // ── SignatureRecipient ────────────────────────────────────────────────

    @Test
    void recipient_isSigner_nullRoleDefaultsToSigner() {
        assertThat(SignatureRecipient.builder().build().isSigner()).isTrue();
        assertThat(SignatureRecipient.builder().role(SignatureRecipientRole.SIGNER).build().isSigner()).isTrue();
        assertThat(SignatureRecipient.builder().role(SignatureRecipientRole.CC).build().isSigner()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(SignatureRecipientStatus.class)
    void recipient_isActionable_onlyPendingOrViewedSigners(SignatureRecipientStatus status) {
        boolean expected = status == SignatureRecipientStatus.PENDING || status == SignatureRecipientStatus.VIEWED;
        assertThat(SignatureRecipient.builder().role(SignatureRecipientRole.SIGNER).status(status).build().isActionable())
                .isEqualTo(expected);
        assertThat(SignatureRecipient.builder().role(null).status(status).build().isActionable()).isEqualTo(expected);
        assertThat(SignatureRecipient.builder().role(SignatureRecipientRole.CC).status(status).build().isActionable()).isFalse();
    }

    @Test
    void recipient_isActionable_nullStatus_false() {
        assertThat(SignatureRecipient.builder().build().isActionable()).isFalse();
    }

    @Test
    void recipient_requiresOtp() {
        assertThat(SignatureRecipient.builder().build().requiresOtp()).isFalse();
        assertThat(SignatureRecipient.builder().authMethod(SignatureAuthMethod.NONE).build().requiresOtp()).isFalse();
        assertThat(SignatureRecipient.builder().authMethod(SignatureAuthMethod.EMAIL_OTP).build().requiresOtp()).isTrue();
        assertThat(SignatureRecipient.builder().authMethod(SignatureAuthMethod.SMS_OTP).build().requiresOtp()).isTrue();
    }

    // ── Persistable.isNew ─────────────────────────────────────────────────

    @Test
    void persistable_isNew_defaultsFalse_andIsSettable() {
        UUID id = UUID.randomUUID();
        assertThat(SignatureEnvelope.builder().id(id).build().isNew()).isFalse();
        assertThat(SignatureEnvelope.builder().id(id).isNew(true).build().isNew()).isTrue();
        assertThat(SignatureRecipient.builder().isNew(true).build().isNew()).isTrue();
        assertThat(SignatureField.builder().build().isNew()).isFalse();
        assertThat(SignatureEvent.builder().isNew(true).build().isNew()).isTrue();
        assertThat(SignatureTemplate.builder().build().isNew()).isFalse();
        assertThat(SignatureEnvelope.builder().id(id).build().getId()).isEqualTo(id);
    }
}
