package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.service.SignatureService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** Email OTP hardening, the expiry sweeper and the reminder scheduler entry points. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureOtpAndSchedulerIT extends AbstractSignatureIT {

    @Autowired
    private SignatureService signatureService;
    @Autowired
    private DatabaseClient db;

    private String contributor;

    SignatureOtpAndSchedulerIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("admin-user");
        mails().clear();
    }

    @Test
    void email_otp_gates_signing() {
        UUID docId = uploadPdf(contributor);
        SignatureRecipientInput guarded = new SignatureRecipientInput(null, "Gina", "gina@example.com", 0,
                SignatureRecipientRole.SIGNER, SignatureAuthMethod.EMAIL_OTP, null, "fr",
                List.of(signatureField(0, 0.1, 0.1)), null);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "OTP", List.of(guarded)));
        assertThat(env.recipients().getFirst().authMethod()).isEqualTo(SignatureAuthMethod.EMAIL_OTP);
        String token = mails().tokenFor(env.id(), "gina@example.com");

        PublicSignatureView v = view(token);
        assertThat(v.otpRequired()).isTrue();
        assertThat(v.otpVerified()).isFalse();

        // Signing before verification is forbidden.
        signRaw(token, new ApplySignatureRequest(TINY_PNG, null, null)).expectStatus().isForbidden();

        // Verifying before requesting a code → 410 (nothing issued yet).
        verifyRaw(token, "000000").expectStatus().isEqualTo(410);

        // Request a code: delivered through the mailer.
        getWebTestClient().post().uri(u -> u.path(PUB + "/otp/request").queryParam("token", token).build())
                .exchange().expectStatus().isAccepted();
        String code = mails().otpFor(env.id(), "gina@example.com");
        assertThat(code).hasSize(6).containsOnlyDigits();

        // Wrong code → 403 and counts an attempt; right code → verified.
        verifyRaw(token, "000000").expectStatus().isForbidden();
        PublicSignatureView verified = verifyRaw(token, code).expectStatus().isOk()
                .expectBody(PublicSignatureView.class).returnResult().getResponseBody();
        assertThat(verified.otpVerified()).isTrue();

        // A code is single use.
        verifyRaw(token, code).expectStatus().isEqualTo(410);

        PublicSignatureView signed = sign(token, new ApplySignatureRequest(TINY_PNG, null, null));
        assertThat(signed.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);

        // OTP endpoints reject recipients without OTP.
        SignatureEnvelopeDTO plain = createEnvelope(contributor, request(docId, "Plain", List.of(
                signer("P", "plain@example.com", List.of(signatureField(0, 0.1, 0.1))))));
        String plainToken = mails().tokenFor(plain.id(), "plain@example.com");
        getWebTestClient().post().uri(u -> u.path(PUB + "/otp/request").queryParam("token", plainToken).build())
                .exchange().expectStatus().isEqualTo(409);
    }

    @Test
    void otp_attempts_are_limited() {
        UUID docId = uploadPdf(contributor);
        SignatureRecipientInput guarded = new SignatureRecipientInput(null, "Hal", "hal@example.com", 0,
                null, SignatureAuthMethod.EMAIL_OTP, null, null, List.of(signatureField(0, 0.1, 0.1)), null);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "OTP limit", List.of(guarded)));
        String token = mails().tokenFor(env.id(), "hal@example.com");
        getWebTestClient().post().uri(u -> u.path(PUB + "/otp/request").queryParam("token", token).build())
                .exchange().expectStatus().isAccepted();
        for (int i = 0; i < 5; i++) {
            verifyRaw(token, "999999").expectStatus().isForbidden();
        }
        verifyRaw(token, mails().otpFor(env.id(), "hal@example.com")).expectStatus().isEqualTo(429);
        // A fresh code resets the counter.
        getWebTestClient().post().uri(u -> u.path(PUB + "/otp/request").queryParam("token", token).build())
                .exchange().expectStatus().isAccepted();
        verifyRaw(token, mails().otpFor(env.id(), "hal@example.com")).expectStatus().isOk();
    }

    @Test
    void sms_otp_is_refused_when_the_server_cannot_deliver_it() {
        UUID docId = uploadPdf(contributor);
        // SMS_OTP without phone → 422 (bad input)
        createEnvelopeRaw(contributor, request(docId, "SMS", List.of(new SignatureRecipientInput(null, "S", "sms@example.com", 0,
                null, SignatureAuthMethod.SMS_OTP, null, null, List.of(signatureField(0, 0.1, 0.1)), null))))
                .expectStatus().isEqualTo(422);
        // …and with a valid phone it is still refused, because no SMS sender is registered here:
        // creating it would strand the signer on a /otp/request that can only ever answer 501.
        createEnvelopeRaw(contributor, request(docId, "SMS", List.of(new SignatureRecipientInput(null, "S",
                "sms@example.com", 0, null, SignatureAuthMethod.SMS_OTP, "+33612345678", null,
                List.of(signatureField(0, 0.1, 0.1)), null))))
                .expectStatus().isEqualTo(422);
        // The settings endpoint advertises exactly what the server can deliver.
        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.signatureAuthMethods()).containsExactly("NONE", "EMAIL_OTP");
    }

    @Test
    void sweeper_expires_overdue_envelopes_and_reminders_go_out_when_due() {
        UUID docId = uploadPdf(contributor);
        CreateSignatureEnvelopeRequest req = new CreateSignatureEnvelopeRequest(docId, "Soon expired", null, List.of(
                signer("Ian", "ian@example.com", List.of(signatureField(0, 0.1, 0.1))),
                signer("Jo", "jo@example.com", List.of(signatureField(0, 0.1, 0.5)))),
                1, false, 2, null, true, null);
        SignatureEnvelopeDTO env = createEnvelope(contributor, req);
        assertThat(env.reminderDays()).isEqualTo(2);

        // Nothing is due yet.
        assertThat(signatureService.sendDueReminders().block()).isZero();
        assertThat(signatureService.sweepExpired().block()).isZero();

        // Last-resort DB seam: there is no API to travel in time, so age the envelope directly.
        db.sql("UPDATE signature_envelope SET sent_at = :t, created_at = :t WHERE id = :id")
                .bind("t", OffsetDateTime.now().minusDays(3)).bind("id", env.id())
                .fetch().rowsUpdated().block();

        mails().clear();
        assertThat(signatureService.sendDueReminders().block()).isEqualTo(2);
        assertThat(mails().ofKind("reminder")).extracting(CapturingSignatureMailer.Sent::to)
                .containsExactlyInAnyOrder("ian@example.com", "jo@example.com");
        // Reminder rotated the token: the new link works.
        String ian = mails().tokenFor(env.id(), "ian@example.com");
        assertThat(view(ian).envelopeTitle()).isEqualTo("Soon expired");
        assertThat(getEnvelope(contributor, env.id()).recipients()).allMatch(r -> r.reminderCount() == 1);
        // Not due again right away.
        assertThat(signatureService.sendDueReminders().block()).isZero();

        // Now push the deadline into the past and sweep.
        db.sql("UPDATE signature_envelope SET expires_at = :t WHERE id = :id")
                .bind("t", OffsetDateTime.now().minusMinutes(1)).bind("id", env.id())
                .fetch().rowsUpdated().block();
        assertThat(signatureService.sweepExpired().block()).isEqualTo(1);
        SignatureEnvelopeDTO expired = getEnvelope(contributor, env.id());
        assertThat(expired.status()).isEqualTo(SignatureEnvelopeStatus.EXPIRED);
        // Signer gets 410 Gone; initiator cannot cancel an expired envelope (409).
        signRaw(ian, new ApplySignatureRequest(null, "Ian", null)).expectStatus().isEqualTo(410);
        getWebTestClient().post().uri(SIG + "/" + env.id() + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isEqualTo(409);
        assertThat(signatureService.sweepExpired().block()).isZero();
    }

    private WebTestClient.ResponseSpec verifyRaw(String token, String code) {
        return getWebTestClient().post().uri(u -> u.path(PUB + "/otp/verify").queryParam("token", token).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", code))
                .exchange();
    }
}
