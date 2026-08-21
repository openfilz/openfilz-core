package org.openfilz.dms.e2e.signature;

import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.signature.ApplySignatureRequest;
import org.openfilz.dms.dto.signature.CreateSignatureEnvelopeRequest;
import org.openfilz.dms.dto.signature.PublicSignatureView;
import org.openfilz.dms.dto.signature.SignatureEnvelopeDTO;
import org.openfilz.dms.dto.signature.SignatureFieldInput;
import org.openfilz.dms.dto.signature.SignatureFieldValue;
import org.openfilz.dms.dto.signature.SignatureRecipientInput;
import org.openfilz.dms.e2e.TestContainersKeyCloakConfig;
import org.openfilz.dms.enums.SignatureFieldType;
import org.openfilz.dms.service.signature.SignatureMailer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared plumbing for the e-Sign ITs: real Keycloak JWTs, feature switched on, mail captured.
 * All state is created and asserted through the REST API.
 */
@Import(SignatureTestConfig.class)
public abstract class AbstractSignatureIT extends TestContainersKeyCloakConfig {

    protected static final String SIG = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURES;
    protected static final String TPL = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_SIGNATURE_TEMPLATES;
    protected static final String PUB = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_PUBLIC_SIGNATURES;

    protected static final String CONTRIBUTOR_EMAIL = "admin-user@test.com";

    @Autowired
    protected SignatureMailer signatureMailer;

    protected CapturingSignatureMailer mails() {
        return (CapturingSignatureMailer) signatureMailer;
    }

    public AbstractSignatureIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void signatureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.signature.active", () -> true);
        registry.add("openfilz.signature.web-base-url", () -> "http://web.test/");
    }

    // ── helpers ─────────────────────────────────────────────────────────

    protected UUID uploadPdf(String token) {
        MultipartBodyBuilder builder = newFileBuilder("pdf-example.pdf");
        UploadResponse resp = getWebTestClient().post()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(resp).isNotNull();
        return resp.id();
    }

    protected UUID uploadText(String token) {
        MultipartBodyBuilder builder = newFileBuilder("test.txt");
        UploadResponse resp = getWebTestClient().post()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        return resp.id();
    }

    protected static SignatureFieldInput signatureField(int page, double x, double y) {
        return new SignatureFieldInput(SignatureFieldType.SIGNATURE, page, x, y, 0.3, 0.08, true, "Signature", null);
    }

    protected static SignatureFieldInput field(SignatureFieldType type, double x, double y, boolean required, String label,
                                               Map<String, Object> options) {
        return new SignatureFieldInput(type, 0, x, y, 0.25, 0.05, required, label, options);
    }

    protected static SignatureRecipientInput signer(String name, String email, List<SignatureFieldInput> fields) {
        return new SignatureRecipientInput(null, name, email, 0, null, null, null, null, fields, null);
    }

    protected static SignatureRecipientInput signer(String name, String email, int order, List<SignatureFieldInput> fields) {
        return new SignatureRecipientInput(null, name, email, order, null, null, null, null, fields, null);
    }

    protected static CreateSignatureEnvelopeRequest request(UUID docId, String title, List<SignatureRecipientInput> recipients) {
        return new CreateSignatureEnvelopeRequest(docId, title, "Please sign", recipients, 30, false, null, "en", true, null);
    }

    protected SignatureEnvelopeDTO createEnvelope(String token, CreateSignatureEnvelopeRequest req) {
        SignatureEnvelopeDTO dto = getWebTestClient().post().uri(SIG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange().expectStatus().isOk()
                .expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
        assertThat(dto).isNotNull();
        return dto;
    }

    protected WebTestClient.ResponseSpec createEnvelopeRaw(String token, Object body) {
        return getWebTestClient().post().uri(SIG)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    protected SignatureEnvelopeDTO getEnvelope(String token, UUID id) {
        return getWebTestClient().get().uri(SIG + "/" + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(SignatureEnvelopeDTO.class).returnResult().getResponseBody();
    }

    protected PublicSignatureView view(String signingToken) {
        return getWebTestClient().get().uri(u -> u.path(PUB).queryParam("token", signingToken).build())
                .exchange().expectStatus().isOk()
                .expectBody(PublicSignatureView.class).returnResult().getResponseBody();
    }

    protected PublicSignatureView markViewed(String signingToken) {
        return getWebTestClient().post().uri(u -> u.path(PUB + "/viewed").queryParam("token", signingToken).build())
                .exchange().expectStatus().isOk()
                .expectBody(PublicSignatureView.class).returnResult().getResponseBody();
    }

    protected WebTestClient.ResponseSpec signRaw(String signingToken, ApplySignatureRequest req) {
        return getWebTestClient().post().uri(u -> u.path(PUB + "/sign").queryParam("token", signingToken).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(req)
                .exchange();
    }

    protected PublicSignatureView sign(String signingToken, ApplySignatureRequest req) {
        return signRaw(signingToken, req).expectStatus().isOk()
                .expectBody(PublicSignatureView.class).returnResult().getResponseBody();
    }

    /** Sign every field of the recipient with a plausible value for its type. */
    protected PublicSignatureView signAllFields(String signingToken) {
        PublicSignatureView v = view(signingToken);
        List<SignatureFieldValue> values = v.fields().stream()
                .filter(f -> !f.type().isAuto())
                .map(f -> switch (f.type()) {
                    case SIGNATURE, INITIALS, IMAGE, STAMP -> new SignatureFieldValue(f.id(), null, TINY_PNG);
                    case CHECKBOX -> new SignatureFieldValue(f.id(), "true", null);
                    case NUMBER -> new SignatureFieldValue(f.id(), "42", null);
                    case EMAIL -> new SignatureFieldValue(f.id(), "signer@example.com", null);
                    case PHONE -> new SignatureFieldValue(f.id(), "+33 6 12 34 56 78", null);
                    case RADIO, SELECT -> new SignatureFieldValue(f.id(), "A", null);
                    default -> new SignatureFieldValue(f.id(), "hello", null);
                })
                .toList();
        return sign(signingToken, new ApplySignatureRequest(null, null, values));
    }

    protected byte[] downloadSigned(String token, UUID envelopeId) {
        return getWebTestClient().get().uri(SIG + "/" + envelopeId + "/signed-document")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
    }

    /** 1×1 transparent PNG. */
    protected static final String TINY_PNG = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";
}
