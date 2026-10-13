package org.openfilz.dms.e2e;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DocumentLanguageRequest;
import org.openfilz.dms.dto.response.DocumentLanguageInfo;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.service.language.TestDocx;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openfilz.dms.service.language.TestDocx.ENGLISH;
import static org.openfilz.dms.service.language.TestDocx.FRENCH;
import static org.openfilz.dms.service.language.TestDocx.styles;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * The OnlyOffice editor in the user's language, and the proofing language of Word documents:
 * {@code GET /onlyoffice/config/{id}?lang=}, {@code GET|POST /onlyoffice/language/{id}}.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class OnlyOfficeDocumentLanguageIT extends TestContainersKeyCloakConfig {

    private static final String LANGUAGE = RestApiVersion.API_PREFIX + "/onlyoffice/language/";

    private String contributor;
    private String reader;

    public OnlyOfficeDocumentLanguageIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void registerOnlyOfficeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.features.custom-access", () -> false);
        registry.add("onlyoffice.enabled", () -> true);
        registry.add("onlyoffice.document-server.url", () -> "http://localhost:9980");
        registry.add("onlyoffice.jwt.enabled", () -> true);
        registry.add("onlyoffice.jwt.secret", () -> "openfilz-onlyoffice-test-jwt-secret-2024");
    }

    @BeforeEach
    void setUp() {
        contributor = getAccessToken("contributor-user");
        reader = getAccessToken("reader-user");
    }

    @Test
    void editorInterfaceFollowsTheRequestedLanguage() {
        UUID id = upload("ui-lang.docx", TestDocx.build(styles(""), FRENCH));

        config(id, "fr").jsonPath("$.config.editorConfig.lang").isEqualTo("fr");
        config(id, "pt-br").jsonPath("$.config.editorConfig.lang").isEqualTo("pt-BR");
        config(id, null).jsonPath("$.config.editorConfig.lang").isEqualTo("en");
        config(id, "fr\"><script>").jsonPath("$.config.editorConfig.lang").isEqualTo("en");
    }

    @Test
    void suggestsTheLanguageOfADocumentThatDeclaresNone_andSetsIt() {
        UUID id = upload("sans-langue.docx", TestDocx.build(styles("<w:style w:styleId=\"Normal\"/>"), FRENCH, FRENCH));

        DocumentLanguageInfo before = getLanguage(id, contributor);
        assertThat(before.supported()).isTrue();
        assertThat(before.editable()).isTrue();
        assertThat(before.declared()).isNull();
        assertThat(before.detected()).isEqualTo("fr-FR");
        assertThat(before.suggested()).isTrue();

        DocumentLanguageInfo after = setLanguage(id, "fr-FR", contributor)
                .expectStatus().isOk()
                .expectBody(DocumentLanguageInfo.class).returnResult().getResponseBody();
        assertThat(after.declared()).isEqualTo("fr-FR");
        assertThat(after.suggested()).isFalse();

        // the stored content now carries the language, read back through the download endpoint
        Map<String, String> parts = TestDocx.parts(download(id));
        assertThat(parts.get("word/styles.xml"))
                .contains("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"fr-FR\"/></w:rPr></w:rPrDefault></w:docDefaults>");
        assertThat(getLanguage(id, contributor).suggested()).isFalse();
    }

    @Test
    void suggestsWhenTheDeclaredLanguageIsNotTheText_andMovesItsRuns() {
        String styles = styles("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"en-US\"/></w:rPr></w:rPrDefault></w:docDefaults>");
        Map<String, String> parts = TestDocx.parts(TestDocx.build(styles, FRENCH));
        parts.put("word/document.xml", parts.get("word/document.xml").replace("<w:r>", "<w:r><w:rPr><w:lang w:val=\"en-US\"/></w:rPr>"));
        UUID id = upload("mauvaise-langue.docx", TestDocx.zip(parts));

        DocumentLanguageInfo before = getLanguage(id, contributor);
        assertThat(before.declared()).isEqualTo("en-US");
        assertThat(before.detected()).isEqualTo("fr-FR");
        assertThat(before.suggested()).isTrue();

        setLanguage(id, "fr-FR", contributor).expectStatus().isOk();

        Map<String, String> stored = TestDocx.parts(download(id));
        assertThat(stored.get("word/document.xml")).contains("w:val=\"fr-FR\"").doesNotContain("en-US");
    }

    @Test
    void doesNotSuggestWhenTheDeclaredLanguageMatches() {
        String styles = styles("<w:docDefaults><w:rPrDefault><w:rPr><w:lang w:val=\"en-GB\"/></w:rPr></w:rPrDefault></w:docDefaults>");
        UUID id = upload("english.docx", TestDocx.build(styles, ENGLISH));

        DocumentLanguageInfo info = getLanguage(id, contributor);
        assertThat(info.declared()).isEqualTo("en-GB");
        assertThat(info.detected()).isEqualTo("en-US");
        assertThat(info.suggested()).isFalse();
    }

    @Test
    void otherFileTypesAreNotSupported() {
        UUID id = upload("notes.txt", FRENCH.getBytes());

        assertThat(getLanguage(id, contributor).supported()).isFalse();
        setLanguage(id, "fr-FR", contributor).expectStatus().isEqualTo(422);
    }

    @Test
    void refusesAnInvalidLanguage() {
        UUID id = upload("invalid-lang.docx", TestDocx.build(styles(""), FRENCH));

        setLanguage(id, "fr", contributor).expectStatus().isBadRequest();
        setLanguage(id, "fr-FR\" w:x=\"", contributor).expectStatus().isBadRequest();
    }

    @Test
    void readerCanNeitherInspectNorSet() {
        UUID id = upload("reader.docx", TestDocx.build(styles(""), FRENCH));

        webTestClient.get().uri(LANGUAGE + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + reader)
                .exchange()
                .expectStatus().isForbidden();
        setLanguage(id, "fr-FR", reader).expectStatus().isForbidden();
    }

    @Test
    void unknownDocument() {
        webTestClient.get().uri(LANGUAGE + UUID.randomUUID())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange()
                .expectStatus().isNotFound();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private UUID upload(String name, byte[] content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content)).filename(name);
        UploadResponse response = getUploadDocumentExchange(builder, contributor)
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertThat(response).isNotNull();
        return response.id();
    }

    private WebTestClient.BodyContentSpec config(UUID id, String lang) {
        return webTestClient.get()
                .uri(b -> {
                    b.path(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + id);
                    if (lang != null) {
                        b.queryParam("lang", lang);
                    }
                    return b.build();
                })
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private DocumentLanguageInfo getLanguage(UUID id, String token) {
        return webTestClient.get().uri(LANGUAGE + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentLanguageInfo.class).returnResult().getResponseBody();
    }

    private WebTestClient.ResponseSpec setLanguage(UUID id, String language, String token) {
        return webTestClient.post().uri(LANGUAGE + id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new DocumentLanguageRequest(language))
                .exchange();
    }

    private byte[] download(UUID id) {
        return webTestClient.get().uri(RestApiVersion.API_PREFIX + "/documents/" + id + "/download")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributor)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
    }
}
