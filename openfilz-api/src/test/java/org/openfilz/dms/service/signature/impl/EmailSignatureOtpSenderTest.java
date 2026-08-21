package org.openfilz.dms.service.signature.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.service.signature.SignatureMailer;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class EmailSignatureOtpSenderTest {

    private final SignatureMailer mailer = mock(SignatureMailer.class);
    private final EmailSignatureOtpSender sender = new EmailSignatureOtpSender(mailer);

    @Test
    void supports_onlyEmailOtp() {
        assertThat(sender.supports(SignatureAuthMethod.EMAIL_OTP)).isTrue();
        assertThat(sender.supports(SignatureAuthMethod.SMS_OTP)).isFalse();
        assertThat(sender.supports(SignatureAuthMethod.NONE)).isFalse();
        assertThat(sender.supports(null)).isFalse();
    }

    @Test
    void send_delegatesToMailer_lazily() {
        SignatureEnvelope env = SignatureEnvelope.builder().id(UUID.randomUUID()).build();
        SignatureRecipient r = SignatureRecipient.builder().id(UUID.randomUUID()).recipientEmail("r@x.io").build();

        var mono = sender.send(env, r, "654321", 7);
        verifyNoInteractions(mailer); // nothing happens before subscription

        StepVerifier.create(mono).verifyComplete();
        verify(mailer).sendOtp(env, r, "654321", 7);
    }
}
