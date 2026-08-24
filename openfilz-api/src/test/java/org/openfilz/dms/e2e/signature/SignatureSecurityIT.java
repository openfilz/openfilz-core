package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.response.Settings;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
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

/** Role matrix for the e-Sign endpoints + the public chain + the settings flag. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureSecurityIT extends AbstractSignatureIT {

    SignatureSecurityIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @Test
    void role_matrix() {
        String contributor = getAccessToken("admin-user");
        String reader = getAccessToken("reader-user");
        String noaccess = getAccessToken("test-user");
        UUID docId = uploadPdf(contributor);

        // Anonymous → 401 on the initiator surface
        getWebTestClient().get().uri(SIG).exchange().expectStatus().isUnauthorized();
        getWebTestClient().get().uri(TPL).exchange().expectStatus().isUnauthorized();

        // READER may list (GET) but not create (POST)
        getWebTestClient().get().uri(SIG).header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isOk();
        getWebTestClient().get().uri(SIG + "/to-sign").header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isOk();
        createEnvelopeRaw(reader, request(docId, "x", List.of(signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))))
                .expectStatus().isForbidden();

        // user without any role → 403
        getWebTestClient().get().uri(SIG).header(HttpHeaders.AUTHORIZATION, "Bearer " + noaccess)
                .exchange().expectStatus().isForbidden();

        // CONTRIBUTOR (no CLEANER role) may delete their own template — DELETE on templates is not the CLEANER rule
        var tpl = getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SignatureTemplateRequest("t", null, null,
                        List.of(new SignatureTemplateRole("A", 0, null, null)),
                        List.of(new SignatureTemplateField("A", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, null, null)),
                        null, null, null))
                .exchange().expectStatus().isOk()
                .expectBody(org.openfilz.dms.dto.signature.SignatureTemplateDTO.class).returnResult().getResponseBody();
        getWebTestClient().delete().uri(TPL + "/" + tpl.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isForbidden();
        getWebTestClient().delete().uri(TPL + "/" + tpl.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNoContent();

        // Envelopes are only manageable by their initiator
        SignatureEnvelopeDTO env = createEnvelope(contributor, request(docId, "mine", List.of(
                signer("A", "a@example.com", List.of(signatureField(0, 0.1, 0.1))))));
        String other = getAccessToken("contributor-user");
        getWebTestClient().post().uri(SIG + "/" + env.id() + "/cancel")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isForbidden();
        getWebTestClient().get().uri(SIG + "/" + env.id() + "/events")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isForbidden();
        getWebTestClient().get().uri(SIG + "/" + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNotFound();

        // Public chain: reachable without a bearer (the token is the authenticator) — bad token = 404, not 401
        getWebTestClient().get().uri(u -> u.path(PUB).queryParam("token", "garbage").build())
                .exchange().expectStatus().isNotFound();
        getWebTestClient().post().uri(u -> u.path(PUB + "/viewed").queryParam("token", "garbage").build())
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void settings_expose_the_flag() {
        String reader = getAccessToken("reader-user");
        Settings settings = getWebTestClient().get().uri("/api/v1/settings")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange().expectStatus().isOk()
                .expectBody(Settings.class).returnResult().getResponseBody();
        assertThat(settings.signatureActive()).isTrue();
    }
}
