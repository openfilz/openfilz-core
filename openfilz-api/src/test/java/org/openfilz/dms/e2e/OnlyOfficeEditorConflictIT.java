package org.openfilz.dms.e2e;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.audit.EditorConflictAudit;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.MoveRequest;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.AuditAction;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ByteArrayResource;
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
import tools.jackson.databind.JsonNode;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * An editor save never overwrites what it did not start from. Callbacks are posted directly, as the
 * document server would, with no browser: the sequence of a real lost update is config (the session
 * opens), a write through the API, then the session's save.
 * <p>
 * Runs on storage without versions; {@link OnlyOfficeEditorConflictVersionedIT} runs the same
 * sequences on versioned storage, where the comparison goes through another path.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class OnlyOfficeEditorConflictIT extends TestContainersKeyCloakConfig {

    private static final String SECRET = "very-long-secret-key-for-editor-conflict-tests-0123456789";
    private static final String DOCS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_DOCUMENTS;

    private final SecretKey secretKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    private MockWebServer documentServer;
    private String token;

    public OnlyOfficeEditorConflictIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void onlyOfficeProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.features.custom-access", () -> false);
        registry.add("onlyoffice.enabled", () -> true);
        registry.add("onlyoffice.document-server.url", () -> "http://localhost:9980");
        registry.add("onlyoffice.document-server.api-path", () -> "/web-apps/apps/api/documents/api.js");
        registry.add("onlyoffice.api-base-url", () -> "http://localhost:8081");
        registry.add("onlyoffice.jwt.enabled", () -> true);
        registry.add("onlyoffice.jwt.secret", () -> SECRET);
        registry.add("onlyoffice.jwt.expiration-seconds", () -> 3600);
        registry.add("onlyoffice.supported-extensions", () -> "docx,doc,xlsx,xls,pptx,ppt,odt,ods,odp");
    }

    @BeforeEach
    void start() throws IOException {
        documentServer = new MockWebServer();
        documentServer.start();
        token = getAccessToken("contributor-user");
    }

    @AfterEach
    void stop() throws IOException {
        documentServer.shutdown();
    }

    @Test
    @DisplayName("a replacement during a session stays current, and the session's save becomes a copy next to it")
    void replacementDuringASessionIsKept() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);

        replace(document, "replaced while the editor was open");
        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "from the editor");

        assertThat(content(document)).isEqualTo("replaced while the editor was open");
        FolderElementInfo copy = onlyCopyIn(folder);
        assertThat(copy.name()).matches("report \\(editor copy \\d{4}-\\d{2}-\\d{2} \\d{2}-\\d{2}-\\d{2}\\)\\.docx");
        assertThat(content(copy.id())).isEqualTo("from the editor");

        List<AuditLog> trail = trail(document);
        assertThat(trail).extracting(AuditLog::action).contains(AuditAction.ONLYOFFICE_SAVE_CONFLICT);
        AuditLog conflict = trail.stream().filter(e -> e.action() == AuditAction.ONLYOFFICE_SAVE_CONFLICT).findFirst().orElseThrow();
        assertThat(((EditorConflictAudit) conflict.details()).getCopyId()).isEqualTo(copy.id());
        assertThat(((EditorConflictAudit) conflict.details()).getSessionKey()).isEqualTo(key);
    }

    @Test
    @DisplayName("a force save then a close of the same session save normally, with no copy")
    void forceSaveThenCloseIsNotAConflict() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);

        save(document, key, OnlyOfficeCallbackRequest.Status.FORCE_SAVE, "force saved");
        assertThat(content(document)).isEqualTo("force saved");
        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "closed");

        assertThat(content(document)).isEqualTo("closed");
        assertThat(copiesIn(folder)).isEmpty();
    }

    @Test
    @DisplayName("two users in one session make one session: no copy")
    void twoUsersInOneSessionAreNotAConflict() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);
        assertThat(openEditor(document, getAccessToken("admin-user"))).isEqualTo(key);

        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "edited together");

        assertThat(content(document)).isEqualTo("edited together");
        assertThat(copiesIn(folder)).isEmpty();
    }

    @Test
    @DisplayName("a rename, a move or a metadata change during a session are not conflicts")
    void changesThatLeaveTheContentAloneAreNotConflicts() {
        UUID folder = folder();
        UUID other = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);

        webTestClient.put().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES + "/{id}/rename", document)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("newName", "renamed-" + UUID.randomUUID() + ".docx"))
                .exchange().expectStatus().isOk();
        webTestClient.post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FILES + "/move")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MoveRequest(List.of(document), other, false))
                .exchange().expectStatus().is2xxSuccessful();

        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "edited");

        assertThat(content(document)).isEqualTo("edited");
        assertThat(copiesIn(folder)).isEmpty();
        assertThat(copiesIn(other)).isEmpty();
    }

    @Test
    @DisplayName("once a session has diverged, its later saves update its one copy")
    void laterSavesOfADivergedSessionUpdateTheCopy() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);

        replace(document, "replaced");
        save(document, key, OnlyOfficeCallbackRequest.Status.FORCE_SAVE, "first save");
        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "last save");

        assertThat(content(document)).isEqualTo("replaced");
        assertThat(content(onlyCopyIn(folder).id())).isEqualTo("last save");
    }

    @Test
    @DisplayName("a session opened after the replacement starts from it, and saves normally")
    void aSessionOpenedAfterTheWriteIsNotAConflict() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        replace(document, "replaced");
        String key = openEditor(document);

        save(document, key, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "edited");

        assertThat(content(document)).isEqualTo("edited");
        assertThat(copiesIn(folder)).isEmpty();
    }

    @Test
    @DisplayName("a session closed without changes, then reopened after a write, starts from the current content")
    void aClosedSessionLeavesNothingBehind() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);
        String key = openEditor(document);
        post(document, callback(OnlyOfficeCallbackRequest.Status.CLOSED, key, null));

        // Same second, same key: the reopened session must not inherit the closed one's base
        replace(document, "replaced");
        String reopened = openEditor(document);
        save(document, reopened, OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "edited");

        assertThat(content(document)).isEqualTo("edited");
        assertThat(copiesIn(folder)).isEmpty();
    }

    @Test
    @DisplayName("a key nobody opened saves as before")
    void aSaveWithNoKnownSessionStillSaves() {
        UUID folder = folder();
        UUID document = upload("report.docx", "opened", folder);

        save(document, document + "_" + System.currentTimeMillis(), OnlyOfficeCallbackRequest.Status.READY_FOR_SAVE, "saved");

        assertThat(content(document)).isEqualTo("saved");
        assertThat(copiesIn(folder)).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private UUID folder() {
        FolderResponse folder = webTestClient.post().uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new CreateFolderRequest("conflict-" + UUID.randomUUID(), null))
                .exchange().expectStatus().isCreated()
                .expectBody(FolderResponse.class).returnResult().getResponseBody();
        return folder.id();
    }

    private static MultipartBodyBuilder multipart(String name, String content) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(content.getBytes(StandardCharsets.UTF_8))).filename(name);
        return builder;
    }

    private UUID upload(String name, String content, UUID folder) {
        MultipartBodyBuilder builder = multipart(name, content);
        builder.part("parentFolderId", folder.toString());
        UploadResponse response = webTestClient.post().uri(DOCS + "/upload")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange().expectStatus().isCreated()
                .expectBody(UploadResponse.class).returnResult().getResponseBody();
        return response.id();
    }

    private void replace(UUID document, String content) {
        webTestClient.put().uri(DOCS + "/{id}/replace-content", document)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(multipart("report.docx", content).build()))
                .exchange().expectStatus().isOk();
    }

    private String openEditor(UUID document) {
        return openEditor(document, token);
    }

    private String openEditor(UUID document, String accessToken) {
        JsonNode config = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/{id}", document)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .exchange().expectStatus().isOk()
                .expectBody(JsonNode.class).returnResult().getResponseBody();
        return config.path("config").path("document").path("key").asString();
    }

    private void save(UUID document, String key, int status, String content) {
        Buffer body = new Buffer();
        body.writeUtf8(content);
        documentServer.enqueue(new MockResponse().setResponseCode(200).setBody(body));
        String url = "http://localhost:" + documentServer.getPort() + "/cache/files/" + key + "/output.docx";
        post(document, callback(status, key, url));
    }

    private static OnlyOfficeCallbackRequest callback(int status, String key, String url) {
        return new OnlyOfficeCallbackRequest(status, key, url, List.of("contributor-user"), null, null,
                List.of(new OnlyOfficeCallbackRequest.Action(0, "contributor-user")), null, null);
    }

    private void post(UUID document, OnlyOfficeCallbackRequest callback) {
        Date now = new Date();
        String callbackToken = Jwts.builder()
                .claim("documentId", document.toString())
                .claim("payload", Map.of("key", callback.key(),
                        "actions", List.of(Map.of("type", 1, "userid", "contributor-user"))))
                .issuedAt(now)
                .expiration(new Date(now.getTime() + 3_600_000))
                .signWith(secretKey)
                .compact();
        webTestClient.post()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + document)
                        .queryParam("token", callbackToken).build())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(callback)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.error").isEqualTo(0);
    }

    private String content(UUID document) {
        byte[] bytes = webTestClient.get().uri(DOCS + "/{id}/download", document)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private FolderElementInfo onlyCopyIn(UUID folder) {
        List<FolderElementInfo> copies = copiesIn(folder);
        assertThat(copies).hasSize(1);
        return copies.getFirst();
    }

    private List<FolderElementInfo> copiesIn(UUID folder) {
        List<FolderElementInfo> elements = webTestClient.get()
                .uri(u -> u.path(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_FOLDERS + "/list")
                        .queryParam("folderId", folder).build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .exchange().expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class).returnResult().getResponseBody();
        return elements.stream().filter(e -> e.name().contains("editor copy")).toList();
    }

    private List<AuditLog> trail(UUID document) {
        return webTestClient.get().uri(RestApiVersion.API_PREFIX + "/audit/{id}", document)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + getAccessToken("admin-user"))
                .exchange().expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<AuditLog>>() {
                }).returnResult().getResponseBody();
    }
}
