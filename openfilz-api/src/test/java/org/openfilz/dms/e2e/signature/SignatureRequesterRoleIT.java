package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateField;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.enums.SignatureFieldType;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Role matrix with {@code openfilz.signature.require-requester-role=true}: initiating signature
 * requests (envelope / template writes) additionally requires SIGN_REQUESTER, while reads and the
 * recipient-facing surface stay untouched. admin-user carries the role in the test realm;
 * contributor-user (plain CONTRIBUTOR) is the denial fixture.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "openfilz.signature.require-requester-role=true")
@TestConstructor(autowireMode = ALL)
class SignatureRequesterRoleIT extends AbstractSignatureIT {

    SignatureRequesterRoleIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    private static SignatureTemplateRequest templateRequest() {
        return new SignatureTemplateRequest("requester-role-tpl", null, null,
                List.of(new SignatureTemplateRole("A", 0, null, null)),
                List.of(new SignatureTemplateField("A", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, null, null)),
                null, null, null);
    }

    @Test
    void contributor_without_requester_role_cannot_initiate() {
        String requester = getAccessToken("admin-user");
        String plainContributor = getAccessToken("contributor-user");
        UUID docId = uploadPdf(requester);

        // Envelope + template writes → 403 without SIGN_REQUESTER
        createEnvelopeRaw(plainContributor, request(docId, "denied", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))))
                .expectStatus().isForbidden();
        getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + plainContributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(templateRequest())
                .exchange().expectStatus().isForbidden();

        // Reads are never gated by the requester role — recipients must still see their queue
        getWebTestClient().get().uri(SIG).header(HttpHeaders.AUTHORIZATION, "Bearer " + plainContributor)
                .exchange().expectStatus().isOk();
        getWebTestClient().get().uri(SIG + "/to-sign").header(HttpHeaders.AUTHORIZATION, "Bearer " + plainContributor)
                .exchange().expectStatus().isOk();
    }

    @Test
    void requester_role_holder_can_initiate() {
        String requester = getAccessToken("admin-user");
        UUID docId = uploadPdf(requester);

        SignatureEnvelopeDTO env = createEnvelope(requester, request(docId, "allowed", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))));
        assertThat(env.id()).isNotNull();

        var tpl = getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(templateRequest())
                .exchange().expectStatus().isOk()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();
        getWebTestClient().delete().uri(TPL + "/" + tpl.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + requester)
                .exchange().expectStatus().isNoContent();
    }

    @Test
    void settings_expose_the_requirement() {
        String reader = getAccessToken("reader-user");
        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.signatureActive()).isTrue();
        assertThat(settings.signatureRequesterRoleRequired()).isTrue();
    }
}
