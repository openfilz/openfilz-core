package org.openfilz.dms.service.signature.impl;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.entity.SignatureRecipient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingSignatureMailerTest {

    private final LoggingSignatureMailer mailer = new LoggingSignatureMailer();
    private final SignatureEnvelope env = SignatureEnvelope.builder()
            .id(UUID.randomUUID()).title("t").initiatorEmail("init@x.io").build();
    private final SignatureRecipient r = SignatureRecipient.builder()
            .id(UUID.randomUUID()).recipientEmail("r@x.io").build();

    @Test
    void everyMethod_onlyLogs_andNeverThrows() {
        assertThatCode(() -> {
            mailer.sendRequest(env, r, "doc.pdf", "https://x/sign?token=1");
            mailer.sendReminder(env, r, "doc.pdf", "https://x/sign?token=2");
            mailer.sendOtp(env, r, "123456", 10);
            mailer.sendCompleted(env, "to@x.io", "To", "fr", new byte[]{1, 2, 3}, "signed.pdf");
            mailer.sendCompleted(env, "to@x.io", null, null, null, null);
            mailer.sendDeclined(env, r);
        }).doesNotThrowAnyException();
    }
}
