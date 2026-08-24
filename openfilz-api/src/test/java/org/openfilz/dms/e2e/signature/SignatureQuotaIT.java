package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Fair-use quota, configured the way a public demo would: two envelopes per initiator per month.
 *
 * Counts are read back through {@code GET /signatures} and the test only creates what is
 * *missing* from the ceiling — the ITs share one database, so an absolute count would depend on
 * which classes ran first.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureQuotaIT extends AbstractSignatureIT {

    private static final int QUOTA = 2;

    private String contributor;

    SignatureQuotaIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void quotaProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.signature.quota.envelopes-per-month", () -> QUOTA);
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("admin-user");
        mails().clear();
    }

    @Test
    void the_ceiling_is_enforced_per_initiator_and_drafts_count() {
        UUID docId = uploadPdf(contributor);

        fillAllowance(contributor, docId);
        createEnvelopeRaw(contributor, envelope(docId, "Over quota")).expectStatus().isEqualTo(429);

        // A draft is refused just the same: it can be sent later, so letting drafts through
        // would leave the ceiling trivially bypassable.
        createEnvelopeRaw(contributor, draft(docId)).expectStatus().isEqualTo(429);

        // The counter is per initiator. A second user is judged only on their own history and
        // keeps being served while the first one is blocked.
        String other = getAccessToken("contributor-user");
        UUID otherDoc = uploadPdf(other);
        fillAllowance(other, otherDoc);
        createEnvelopeRaw(other, envelope(otherDoc, "Over quota")).expectStatus().isEqualTo(429);
    }

    /** Create exactly the envelopes this initiator still has left, each of which must succeed. */
    private void fillAllowance(String token, UUID docId) {
        int remaining = QUOTA - envelopesAlreadyCreated(token);
        for (int i = 0; i < remaining; i++) {
            createEnvelope(token, envelope(docId, "Within quota " + i));
        }
    }

    /**
     * Everything an IT run creates lands in the current month, so the full list is the month's
     * usage.
     */
    private int envelopesAlreadyCreated(String token) {
        List<SignatureEnvelopeDTO> sent = getWebTestClient().get().uri(SIG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        return sent == null ? 0 : sent.size();
    }

    private CreateSignatureEnvelopeRequest envelope(UUID docId, String title) {
        return request(docId, title,
                List.of(signer("Ada Lovelace", "ada@example.com", List.of(signatureField(0, 0.1, 0.1)))));
    }

    private CreateSignatureEnvelopeRequest draft(UUID docId) {
        return new CreateSignatureEnvelopeRequest(docId, "Over quota, as a draft", "Please sign",
                List.of(signer("Ada Lovelace", "ada@example.com", List.of(signatureField(0, 0.1, 0.1)))),
                30, false, null, "en", false, null);
    }
}
