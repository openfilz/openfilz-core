package org.openfilz.dms.e2e.signature;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.dto.signature.InstantiateTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateField;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.enums.SignatureAuthMethod;
import org.openfilz.dms.enums.SignatureEnvelopeStatus;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/** Template CRUD (owner-scoped) + instantiation into a real envelope. */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
class SignatureTemplateIT extends AbstractSignatureIT {

    private String contributor;
    private String other;

    SignatureTemplateIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("admin-user");
        other = getAccessToken("contributor-user");
        mails().clear();
    }

    private static SignatureTemplateRequest twoRoleTemplate(UUID docId, String name) {
        return new SignatureTemplateRequest(name, "NDA between a client and our sales rep", docId,
                List.of(new SignatureTemplateRole("Client", 0, SignatureRecipientRole.SIGNER, SignatureAuthMethod.NONE),
                        new SignatureTemplateRole("Sales", 1, SignatureRecipientRole.SIGNER, null),
                        new SignatureTemplateRole("Legal", 0, SignatureRecipientRole.CC, null)),
                List.of(new SignatureTemplateField("Client", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, "Client signature", null),
                        new SignatureTemplateField("Client", SignatureFieldType.TEXT, 0, 0.1, 0.3, 0.3, 0.05, true, "Company", null),
                        new SignatureTemplateField("Client", SignatureFieldType.SELECT, 0, 0.1, 0.4, 0.3, 0.05, true, "Plan",
                                Map.of("choices", List.of("Basic", "Pro"))),
                        new SignatureTemplateField("Sales", SignatureFieldType.SIGNATURE, 0, 0.6, 0.1, 0.3, 0.08, true, null, null)),
                "Please review and sign the NDA", 15, true);
    }

    @Test
    void crud_is_owner_scoped() {
        UUID docId = uploadPdf(contributor);
        SignatureTemplateDTO created = getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(twoRoleTemplate(docId, "NDA"))
                .exchange().expectStatus().isOk()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();
        assertThat(created.id()).isNotNull();
        assertThat(created.ownerEmail()).isEqualTo(CONTRIBUTOR_EMAIL);
        assertThat(created.roles()).hasSize(3);
        assertThat(created.fields()).hasSize(4);
        assertThat(created.fields().get(2).options()).containsEntry("choices", List.of("Basic", "Pro"));
        assertThat(created.sequential()).isTrue();

        // list / get
        List<SignatureTemplateDTO> mine = getWebTestClient().get().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureTemplateDTO.class).returnResult().getResponseBody();
        assertThat(mine).extracting(SignatureTemplateDTO::id).contains(created.id());
        List<SignatureTemplateDTO> admins = getWebTestClient().get().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isOk()
                .expectBodyList(SignatureTemplateDTO.class).returnResult().getResponseBody();
        assertThat(admins).extracting(SignatureTemplateDTO::id).doesNotContain(created.id());
        getWebTestClient().get().uri(TPL + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isForbidden();

        // update
        SignatureTemplateDTO updated = getWebTestClient().put().uri(TPL + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(twoRoleTemplate(docId, "NDA v2"))
                .exchange().expectStatus().isOk()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();
        assertThat(updated.name()).isEqualTo("NDA v2");

        // validation: field bound to unknown role / duplicate role
        getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SignatureTemplateRequest("bad", null, null,
                        List.of(new SignatureTemplateRole("A", 0, null, null)),
                        List.of(new SignatureTemplateField("B", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, null, null)),
                        null, null, null))
                .exchange().expectStatus().isEqualTo(422);
        getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SignatureTemplateRequest("bad", null, null,
                        List.of(new SignatureTemplateRole("A", 0, null, null), new SignatureTemplateRole("A", 1, null, null)),
                        List.of(new SignatureTemplateField("A", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, null, null)),
                        null, null, null))
                .exchange().expectStatus().isEqualTo(422);

        // delete (owner only)
        getWebTestClient().delete().uri(TPL + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + other)
                .exchange().expectStatus().isForbidden();
        getWebTestClient().delete().uri(TPL + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNoContent();
        getWebTestClient().get().uri(TPL + "/" + created.id())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void instantiate_binds_roles_to_people_and_sends_sequentially() {
        UUID docId = uploadPdf(contributor);
        SignatureTemplateDTO tpl = getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(twoRoleTemplate(docId, "NDA"))
                .exchange().expectStatus().isOk()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();

        // Missing role binding → 422; unknown role → 422
        instantiateRaw(tpl.id(), new InstantiateTemplateRequest(null, null, null, List.of(
                new InstantiateTemplateRequest.RoleBinding("Client", null, "C", "c@example.com", null, null)),
                null, null, null, true)).expectStatus().isEqualTo(422);
        instantiateRaw(tpl.id(), new InstantiateTemplateRequest(null, null, null, List.of(
                new InstantiateTemplateRequest.RoleBinding("Nope", null, "C", "c@example.com", null, null)),
                null, null, null, true)).expectStatus().isEqualTo(422);

        SignatureEnvelopeDTO env = instantiateRaw(tpl.id(), new InstantiateTemplateRequest(null, "NDA — ACME", null, List.of(
                new InstantiateTemplateRequest.RoleBinding("Client", null, "Acme Inc", "client@acme.example", null, "fr"),
                new InstantiateTemplateRequest.RoleBinding("Sales", null, "Sam Sales", "sam@example.com", null, null),
                new InstantiateTemplateRequest.RoleBinding("Legal", null, "Legal", "legal@example.com", null, null)),
                null, null, null, true))
                .expectStatus().isOk().expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(env.templateId()).isEqualTo(tpl.id());
        assertThat(env.title()).isEqualTo("NDA — ACME");
        assertThat(env.message()).isEqualTo("Please review and sign the NDA");
        assertThat(env.sequential()).isTrue();
        assertThat(env.status()).isEqualTo(SignatureEnvelopeStatus.SENT);
        assertThat(env.recipients()).hasSize(3);
        assertThat(env.recipients().stream().filter(r -> r.email().equals("client@acme.example")).findFirst().orElseThrow().fields()).hasSize(3);
        assertThat(env.recipients().stream().filter(r -> r.email().equals("legal@example.com")).findFirst().orElseThrow().role())
                .isEqualTo(SignatureRecipientRole.CC);
        // Sequential: only the client is invited first
        assertThat(mails().ofKind("request")).extracting(CapturingSignatureMailer.Sent::to).containsExactly("client@acme.example");

        // Template without default doc and no sourceDocId → 422
        SignatureTemplateDTO noDoc = getWebTestClient().post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SignatureTemplateRequest("no doc", null, null,
                        List.of(new SignatureTemplateRole("A", 0, null, null)),
                        List.of(new SignatureTemplateField("A", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, null, null)),
                        null, null, false))
                .exchange().expectStatus().isOk()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();
        instantiateRaw(noDoc.id(), new InstantiateTemplateRequest(null, null, null, List.of(
                new InstantiateTemplateRequest.RoleBinding("A", null, "A", "a@example.com", null, null)),
                null, null, null, true)).expectStatus().isEqualTo(422);
        // …but works with an explicit document, as a draft
        SignatureEnvelopeDTO draft = instantiateRaw(noDoc.id(), new InstantiateTemplateRequest(docId, null, null, List.of(
                new InstantiateTemplateRequest.RoleBinding("A", null, "A", "a@example.com", null, null)),
                null, null, null, false))
                .expectStatus().isOk().expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(draft.status()).isEqualTo(SignatureEnvelopeStatus.DRAFT);
        assertThat(draft.title()).isEqualTo("no doc");
    }

    private WebTestClient.ResponseSpec instantiateRaw(UUID templateId, InstantiateTemplateRequest req) {
        return getWebTestClient().post().uri(TPL + "/" + templateId + "/envelopes")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange();
    }
}
