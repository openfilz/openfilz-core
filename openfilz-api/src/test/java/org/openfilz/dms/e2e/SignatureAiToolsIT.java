package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.signature.SignatureTemplateDTO;
import org.openfilz.dms.dto.signature.SignatureTemplateField;
import org.openfilz.dms.dto.signature.SignatureTemplateRequest;
import org.openfilz.dms.dto.signature.SignatureTemplateRole;
import org.openfilz.dms.e2e.signature.CapturingSignatureMailer;
import org.openfilz.dms.e2e.signature.SignatureTestConfig;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.enums.SignatureRecipientRole;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The e-Sign tools through the MCP front-end: send a PDF with the default placement, follow the
 * envelope, use a template prepared over REST, and the refusals. Mail is captured, never sent.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(SignatureTestConfig.class)
class SignatureAiToolsIT extends AbstractMcpIT {

    private static final String TPL = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURE_TEMPLATES;
    private static final Pattern ENVELOPE_ID = Pattern.compile("\\(id ([0-9a-f-]{36})\\)");

    @Autowired
    private SignatureMailer signatureMailer;

    SignatureAiToolsIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void signatureProperties(DynamicPropertyRegistry registry) {
        registerModelSelectors(registry, "none");
        registry.add("openfilz.mcp.mode", () -> "READ_WRITE");
        registry.add("openfilz.signature.active", () -> true);
        registry.add("openfilz.signature.web-base-url", () -> "http://web.test/");
    }

    @BeforeEach
    void clearMail() {
        mails().clear();
    }

    @Test
    @DisplayName("sendForSignature with the default placement emails the signer, and the envelope can be followed")
    void sendsWithDefaultPlacementAndTracksTheEnvelope() {
        UUID pdf = uploadPdf();
        String title = "MCP lease " + UUID.randomUUID().toString().substring(0, 8);

        String sent = callToolText("sendForSignature", """
                {"document":"%s","recipients":"Alice Smith <alice@example.com>; cc: copy@example.com","title":"%s","message":"Please sign"}"""
                .formatted(pdf, title));
        assertThat(sent).contains("SENT").contains("Signing invitations").contains("alice@example.com").contains("(copy)");
        UUID envelopeId = envelopeIdOf(sent);
        assertThat(mails().sent).anyMatch(m -> "alice@example.com".equals(m.to()) && envelopeId.equals(m.envelopeId()));

        assertThat(callToolText("listSignatureEnvelopes", "{}")).contains(title).contains("0/1 signed");
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"SENT"}""")).contains(title);
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"COMPLETED"}""")).doesNotContain(title);
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"to-sign"}""")).contains("No envelopes waiting");
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"bogus"}""")).contains("Unknown status");

        String byId = callToolText("getSignatureStatus", """
                {"envelope":"%s"}""".formatted(envelopeId));
        assertThat(byId).contains(title).contains("PENDING").contains("Expires");
        assertThat(callToolText("getSignatureStatus", """
                {"envelope":"%s"}""".formatted(title))).contains(envelopeId.toString());
    }

    @Test
    @DisplayName("a template prepared in the app binds roles to people; sendNow=false keeps a draft")
    void usesATemplateAndKeepsADraft() {
        UUID pdf = uploadPdf();
        String name = "Lease template " + UUID.randomUUID().toString().substring(0, 8);
        SignatureTemplateDTO template = webTestClient.post().uri(TPL)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new SignatureTemplateRequest(name, "two-party lease", null,
                        List.of(new SignatureTemplateRole("Tenant", 0, SignatureRecipientRole.SIGNER, null),
                                new SignatureTemplateRole("Landlord", 1, SignatureRecipientRole.SIGNER, null)),
                        List.of(new SignatureTemplateField("Tenant", SignatureFieldType.SIGNATURE, 0, 0.1, 0.1, 0.3, 0.08, true, "Tenant", null),
                                new SignatureTemplateField("Landlord", SignatureFieldType.SIGNATURE, 0, 0.6, 0.1, 0.3, 0.08, true, "Landlord", null)),
                        "Please sign the lease", 30, false))
                .exchange().expectStatus().is2xxSuccessful()
                .expectBody(SignatureTemplateDTO.class).returnResult().getResponseBody();
        assertThat(template).isNotNull();

        assertThat(callToolText("listSignatureTemplates", "{}")).contains(name).contains("roles Tenant, Landlord");

        String draft = callToolText("sendForSignature", """
                {"document":"%s","recipients":"Landlord: Bob <bob@example.com>; Tenant: alice@example.com","template":"%s","sendNow":false}"""
                .formatted(pdf, name));
        assertThat(draft).contains("DRAFT").contains("draft").contains("bob@example.com").contains("alice@example.com");
        assertThat(mails().sent).isEmpty();

        assertThat(callToolText("sendForSignature", """
                {"document":"%s","recipients":"Buyer: x@example.com; Tenant: y@example.com","template":"%s"}"""
                .formatted(pdf, name))).contains("no role 'Buyer'");
    }

    @Test
    @DisplayName("only PDFs with well-formed recipients are accepted, and a READER may not send")
    void refusals() {
        UUID text = upload("test.txt");
        assertThat(callToolText("sendForSignature", """
                {"document":"%s","recipients":"alice@example.com"}""".formatted(text))).contains("No PDF document");

        UUID pdf = uploadPdf();
        assertThat(callToolText("sendForSignature", """
                {"document":"%s","recipients":"Alice Smith"}""".formatted(pdf))).contains("no email address");
        assertThat(callToolText("sendForSignature", """
                {"document":"%s","recipients":"cc: copy@example.com"}""".formatted(pdf))).contains("must be a signer");
        assertThat(mails().sent).isEmpty();

        String admin = accessToken;
        accessToken = getAccessToken("reader-user");
        try {
            assertThat(callToolText("sendForSignature", """
                    {"document":"%s","recipients":"alice@example.com"}""".formatted(pdf))).contains("Not permitted");
            assertThat(callToolText("listSignatureEnvelopes", "{}")).doesNotContain("Not permitted");
        } finally {
            accessToken = admin;
        }
    }

    // ---------------------------------------------------------------- helpers

    private CapturingSignatureMailer mails() {
        return (CapturingSignatureMailer) signatureMailer;
    }

    private static UUID envelopeIdOf(String answer) {
        Matcher matcher = ENVELOPE_ID.matcher(answer);
        assertThat(matcher.find()).as("the answer names the envelope id; was: %s", answer).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private UUID uploadPdf() {
        return upload("pdf-example.pdf");
    }

    private UUID upload(String fixture) {
        MultipartBodyBuilder builder = newFileBuilder(fixture);
        UploadResponse response = webTestClient.post()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.id();
    }
}
