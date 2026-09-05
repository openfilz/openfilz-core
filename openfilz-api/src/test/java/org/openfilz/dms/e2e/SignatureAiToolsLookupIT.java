package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.e2e.signature.CapturingSignatureMailer;
import org.openfilz.dms.e2e.signature.SignatureTestConfig;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * How the e-Sign tools resolve what the model names: an envelope by title (exact, partial,
 * ambiguous or unknown), a document or template that does not exist, and the listing filters and
 * defaults. Complements {@link SignatureAiToolsIT}, which covers the sending flows themselves.
 * <p>
 * Same context configuration as that suite, so both share one Spring context.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
@Import(SignatureTestConfig.class)
class SignatureAiToolsLookupIT extends AbstractMcpIT {

    @Autowired
    private SignatureMailer signatureMailer;

    SignatureAiToolsLookupIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
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
        ((CapturingSignatureMailer) signatureMailer).clear();
    }

    @Test
    @DisplayName("getSignatureStatus resolves a title partially, refuses an ambiguous or unknown one")
    void envelopesAreResolvedByTitle() {
        UUID pdf = uploadPdf();
        String stem = "Quote " + UUID.randomUUID().toString().substring(0, 8);

        UUID first = envelopeIdOf(send(pdf, stem + " north"));
        send(pdf, stem + " south");

        // A unique substring is enough
        assertThat(callToolText("getSignatureStatus", """
                {"envelope":"%s north"}""".formatted(stem))).contains(first.toString());

        // The common stem matches both, so the tool asks which one
        assertThat(callToolText("getSignatureStatus", """
                {"envelope":"%s"}""".formatted(stem)))
                .contains("Several envelopes match")
                .contains(stem + " north")
                .contains(stem + " south");

        assertThat(callToolText("getSignatureStatus", """
                {"envelope":"no such envelope %s"}""".formatted(UUID.randomUUID())))
                .contains("No envelope titled")
                .contains("listSignatureEnvelopes");

        assertThat(callToolText("getSignatureStatus", """
                {"envelope":"  "}""")).contains("Give the envelope id or title");
    }

    @Test
    @DisplayName("sendForSignature reports what it cannot find, and titles the envelope after the document")
    void unknownReferencesAreReported() {
        UUID pdf = uploadPdf();

        assertThat(callToolText("sendForSignature", """
                {"document":"no-such-document-%s.pdf","recipients":"alice@example.com"}"""
                .formatted(UUID.randomUUID()))).contains("No PDF document");

        assertThat(callToolText("sendForSignature", """
                {"document":"%s","recipients":"alice@example.com","template":"no-such-template-%s"}"""
                .formatted(pdf, UUID.randomUUID()))).contains("template");

        // Neither a document nor a template: the tool asks for the document
        assertThat(callToolText("sendForSignature", """
                {"recipients":"alice@example.com"}""")).contains("Say which PDF document");

        // Without a title the envelope is named after the document, extension stripped
        String sent = callToolText("sendForSignature", """
                {"document":"%s","recipients":"alice@example.com","expiresInDays":7,"sequential":true}"""
                .formatted(pdf));
        assertThat(sent).contains("pdf-example").doesNotContain("pdf-example.pdf");
    }

    @Test
    @DisplayName("the listing filters accept the documented aliases and report an empty shelf")
    void listingFiltersAndEmptyResults() {
        UUID pdf = uploadPdf();
        String title = "Alias listing " + UUID.randomUUID().toString().substring(0, 8);
        send(pdf, title);

        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"all"}""")).contains(title);
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"sent-by-me"}""")).contains(title);
        // Status names are matched whatever the case and however they are spelled
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"sent"}""")).contains(title);
        assertThat(callToolText("listSignatureEnvelopes", """
                {"status":"DRAFT"}""")).doesNotContain(title);

        // A user who sent nothing sees an empty shelf, not someone else's envelopes
        String admin = accessToken;
        accessToken = getAccessToken("reader-user");
        try {
            assertThat(callToolText("listSignatureEnvelopes", "{}"))
                    .doesNotContain(title).contains("No envelopes you sent");
            assertThat(callToolText("listSignatureTemplates", "{}")).contains("no e-Sign templates");
            assertThat(callToolText("getSignatureStatus", """
                    {"envelope":"%s"}""".formatted(title))).contains("No envelope titled");
        } finally {
            accessToken = admin;
        }
    }

    // ---------------------------------------------------------------- helpers

    private String send(UUID pdf, String title) {
        String answer = callToolText("sendForSignature", """
                {"document":"%s","recipients":"Alice Smith <alice@example.com>","title":"%s"}"""
                .formatted(pdf, title));
        assertThat(answer).as("the envelope was sent; was: %s", answer).contains("SENT");
        return answer;
    }

    private static UUID envelopeIdOf(String answer) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\(id ([0-9a-f-]{36})\\)").matcher(answer);
        assertThat(matcher.find()).as("the answer names the envelope id; was: %s", answer).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    private UUID uploadPdf() {
        MultipartBodyBuilder builder = newFileBuilder("pdf-example.pdf");
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
