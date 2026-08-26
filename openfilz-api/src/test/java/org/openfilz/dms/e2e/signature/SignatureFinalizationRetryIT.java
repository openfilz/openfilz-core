package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.entity.Document;
import org.openfilz.dms.entity.SignatureEnvelope;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureRecipientStatus;
import org.openfilz.dms.service.signature.SignatureCompletionListener;
import org.openfilz.dms.service.signature.SignatureSealer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * A finalization failure (seal provider / archiving outage surfacing through the completion
 * listener) must not wedge the envelope: the recipient's SIGNED status has to roll back with
 * the rest of the completion transaction so the signer can retry once the backend recovers.
 * Also covers the self-heal for envelopes wedged by the historical bug (recipient SIGNED,
 * envelope stuck SENT): re-opening the signing link re-runs finalization.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(SignatureFinalizationRetryIT.FailingCompletionListenerConfig.class)
class SignatureFinalizationRetryIT extends AbstractSignatureIT {

    /** Completion listener standing in for the enterprise archiving call — fails on demand. */
    static class TogglableCompletionListener implements SignatureCompletionListener {
        final AtomicBoolean failing = new AtomicBoolean(false);

        @Override
        public Mono<Void> onCompleted(SignatureEnvelope envelope, Document signedDocument, SignatureSealer.SealResult seal) {
            return failing.get()
                    ? Mono.error(new IllegalStateException("simulated archiving/signing outage"))
                    : Mono.empty();
        }
    }

    @TestConfiguration
    static class FailingCompletionListenerConfig {
        @Bean
        @Primary
        TogglableCompletionListener togglableCompletionListener() {
            return new TogglableCompletionListener();
        }
    }

    @Autowired
    private TogglableCompletionListener completionListener;

    @Autowired
    private DatabaseClient databaseClient;

    private String contributor;

    SignatureFinalizationRetryIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("admin-user");
        mails().clear();
        completionListener.failing.set(false);
    }

    @Test
    void failed_finalization_rolls_back_the_signature_so_the_signer_can_retry() {
        UUID docId = uploadPdf(contributor);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "Flaky seal", List.of(
                signer("Alice", "alice@example.com", List.of(signatureField(0, 0.1, 0.1))))));
        String token = mails().tokenFor(env.id(), "alice@example.com");
        markViewed(token);

        completionListener.failing.set(true);
        signRaw(token, buildAllFieldsRequest(token)).expectStatus().is5xxServerError();

        // The whole attempt rolled back: recipient is still actionable, envelope still open.
        PublicSignatureView after = view(token);
        assertThat(after.recipientStatus()).isEqualTo(SignatureRecipientStatus.VIEWED);
        assertThat(after.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(after.myTurn()).isTrue();

        // Backend recovered — the same link signs successfully.
        completionListener.failing.set(false);
        PublicSignatureView signed = signAllFields(token);
        assertThat(signed.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(signed.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);

        SignatureEnvelopeDTO done = getEnvelope(contributor, env.id());
        assertThat(done.status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(downloadSigned(contributor, env.id())).isNotEmpty();
    }

    @Test
    void reopening_the_link_heals_an_envelope_wedged_by_the_historical_partial_commit() {
        UUID docId = uploadPdf(contributor);
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "Wedged envelope", List.of(
                signer("Alice", "alice@example.com", List.of(signatureField(0, 0.1, 0.1))))));
        String token = mails().tokenFor(env.id(), "alice@example.com");
        markViewed(token);

        // DB seam (no API can produce this state any more): recreate the pre-fix wedge where the
        // recipient row was committed SIGNED but the completion transaction rolled back.
        databaseClient.sql("UPDATE signature_recipient SET status = 'SIGNED', signed_at = now() WHERE envelope_id = :env")
                .bind("env", env.id())
                .fetch().rowsUpdated().block();
        assertThat(getEnvelope(contributor, env.id()).status()).isEqualTo(SignatureEnvelopeStatus.SENT);

        // Re-opening the signing link detects the wedge and re-runs finalization.
        PublicSignatureView healed = markViewed(token);
        assertThat(healed.recipientStatus()).isEqualTo(SignatureRecipientStatus.SIGNED);
        assertThat(healed.envelopeStatus()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);

        SignatureEnvelopeDTO done = getEnvelope(contributor, env.id());
        assertThat(done.status()).isEqualTo(SignatureEnvelopeStatus.COMPLETED);
        assertThat(downloadSigned(contributor, env.id())).isNotEmpty();
    }

    /** Same payload {@link #signAllFields} would send, without executing the request. */
    private ApplySignatureRequest buildAllFieldsRequest(String token) {
        PublicSignatureView v = view(token);
        List<SignatureFieldValue> values = v.fields().stream()
                .filter(f -> !f.type().isAuto())
                .map(f -> new SignatureFieldValue(f.id(), null, TINY_PNG))
                .toList();
        return new ApplySignatureRequest(null, null, values);
    }
}
