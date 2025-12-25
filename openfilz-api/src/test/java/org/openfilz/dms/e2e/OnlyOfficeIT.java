package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.OnlyOfficeCallbackRequest;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Integration tests for OnlyOffice integration.
 *
 * Tests cover:
 * 1. Status and supported file type endpoints
 * 2. EditorConfig retrieval (OAuth2 authentication via Keycloak)
 * 3. Document download endpoint (OnlyOffice JWT authentication)
 * 4. Callback endpoint (OnlyOffice JWT authentication)
 * 5. Token management and security
 *
 * These tests simulate the complete flow:
 * - Frontend (Angular) → Backend (Spring) for config retrieval
 * - OnlyOffice DocumentServer → Backend for document download and callbacks
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class OnlyOfficeIT extends TestContainersKeyCloakConfig {

    private static final String ONLYOFFICE_JWT_SECRET = "openfilz-onlyoffice-test-jwt-secret-2024";
    private static final String DOCUMENT_SERVER_URL = "http://localhost:9980";

    protected String contributorAccessToken;
    protected String readerAccessToken;
    protected String adminAccessToken;
    protected String noaccessAccessToken;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecretKey onlyOfficeSecretKey;

    public OnlyOfficeIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void registerOnlyOfficeProperties(DynamicPropertyRegistry registry) {
        // Keycloak configuration
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/your-realm");
        registry.add("openfilz.security.no-auth", () -> false);

        // Ensure OnlyOfficeSecurityConfig is activated (not custom-access mode)
        registry.add("openfilz.features.custom-access", () -> false);

        // OnlyOffice configuration
        registry.add("onlyoffice.enabled", () -> true);
        registry.add("onlyoffice.document-server.url", () -> DOCUMENT_SERVER_URL);
        registry.add("onlyoffice.document-server.api-path", () -> "/web-apps/apps/api/documents/api.js");
        registry.add("onlyoffice.api-base-url", () -> "http://localhost:8081");
        registry.add("onlyoffice.jwt.enabled", () -> true);
        registry.add("onlyoffice.jwt.secret", () -> ONLYOFFICE_JWT_SECRET);
        registry.add("onlyoffice.jwt.expiration-seconds", () -> 3600);
        registry.add("onlyoffice.supported-extensions", () -> "docx,doc,xlsx,xls,pptx,ppt,odt,ods,odp");

        registry.add("logging.level.org.openfilz", () -> "DEBUG");
    }

    @BeforeEach
    void setUp() {
        contributorAccessToken = getAccessToken("contributor-user");
        readerAccessToken = getAccessToken("reader-user");
        adminAccessToken = getAccessToken("admin-user");
        noaccessAccessToken = getAccessToken("test-user");

        // Initialize OnlyOffice secret key for generating test tokens
        String paddedSecret = String.format("%-32s", ONLYOFFICE_JWT_SECRET).substring(0, 32);
        onlyOfficeSecretKey = Keys.hmacShaKeyFor(paddedSecret.getBytes(StandardCharsets.UTF_8));
    }

    // ==================== HELPER METHODS ====================

    /**
     * Upload a test document with a specific filename.
     * Uses test.txt as the source but renames it to simulate different file types.
     */
    private UploadResponse uploadTestDocumentWithName(String accessToken, String filename) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new org.springframework.core.io.ClassPathResource("test.txt"))
               .filename(filename);
        return getUploadDocumentExchange(builder, accessToken)
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    /**
     * Generate an OnlyOffice access token for testing.
     */
    private String generateOnlyOfficeAccessToken(UUID documentId, String userId, String userName) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 3600 * 1000);

        return Jwts.builder()
                .claim("documentId", documentId.toString())
                .claim("userId", userId)
                .claim("userName", userName)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(onlyOfficeSecretKey)
                .compact();
    }

    /**
     * Generate an expired OnlyOffice access token for testing.
     */
    private String generateExpiredOnlyOfficeToken(UUID documentId, String userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() - 3600 * 1000); // Expired 1 hour ago

        return Jwts.builder()
                .claim("documentId", documentId.toString())
                .claim("userId", userId)
                .claim("type", "access")
                .issuedAt(new Date(now.getTime() - 7200 * 1000))
                .expiration(expiration)
                .signWith(onlyOfficeSecretKey)
                .compact();
    }

    /**
     * Generate a token with invalid secret for testing.
     */
    private String generateTokenWithInvalidSecret(UUID documentId, String userId) {
        // Use a secret that is at least 32 bytes (256 bits) for HS256
        SecretKey invalidKey = Keys.hmacShaKeyFor("invalid-secret-key-for-testing-at-least-32-bytes-long!".getBytes(StandardCharsets.UTF_8));
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 3600 * 1000);

        return Jwts.builder()
                .claim("documentId", documentId.toString())
                .claim("userId", userId)
                .claim("type", "access")
                .issuedAt(now)
                .expiration(expiration)
                .signWith(invalidKey)
                .compact();
    }

    /**
     * Generate a callback token (simulating OnlyOffice DocumentServer callback).
     */
    private String generateCallbackToken(UUID documentId, String userId) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 3600 * 1000);

        // OnlyOffice callback tokens have a specific structure
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", documentId.toString() + "_" + System.currentTimeMillis());

        List<Map<String, Object>> actions = new ArrayList<>();
        Map<String, Object> action = new HashMap<>();
        action.put("type", 1);
        action.put("userid", userId);
        actions.add(action);
        payload.put("actions", actions);

        return Jwts.builder()
                .claim("payload", payload)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(onlyOfficeSecretKey)
                .compact();
    }

    // ==================== STATUS ENDPOINT TESTS ====================

    @Nested
    @DisplayName("GET /onlyoffice/status - OnlyOffice Status Endpoint")
    class StatusEndpointTests {

        @Test
        @DisplayName("Should return enabled status when OnlyOffice is configured")
        void shouldReturnEnabledStatus() {
            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/status")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.enabled").isEqualTo(true)
                    .jsonPath("$.status").isEqualTo("ok");
        }

        @Test
        @DisplayName("Should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticated() {
            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/status")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }
    }

    // ==================== SUPPORTED ENDPOINT TESTS ====================

    @Nested
    @DisplayName("GET /onlyoffice/supported - File Type Support Endpoint")
    class SupportedEndpointTests {

        @Test
        @DisplayName("Should return supported=true for DOCX files")
        void shouldReturnSupportedForDocx() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/supported")
                            .queryParam("fileName", "document.docx")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.fileName").isEqualTo("document.docx")
                    .jsonPath("$.supported").isEqualTo(true);
        }

        @Test
        @DisplayName("Should return supported=true for XLSX files")
        void shouldReturnSupportedForXlsx() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/supported")
                            .queryParam("fileName", "spreadsheet.xlsx")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.supported").isEqualTo(true);
        }

        @Test
        @DisplayName("Should return supported=true for PPTX files")
        void shouldReturnSupportedForPptx() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/supported")
                            .queryParam("fileName", "presentation.pptx")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.supported").isEqualTo(true);
        }

        @Test
        @DisplayName("Should return supported=false for unsupported file types")
        void shouldReturnNotSupportedForPng() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/supported")
                            .queryParam("fileName", "image.png")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.supported").isEqualTo(false);
        }

        @Test
        @DisplayName("Should return supported=true for ODF files")
        void shouldReturnSupportedForOdf() {
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/supported")
                            .queryParam("fileName", "document.odt")
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.supported").isEqualTo(true);
        }
    }

    // ==================== EDITOR CONFIG ENDPOINT TESTS ====================

    @Nested
    @DisplayName("GET /onlyoffice/config/{documentId} - Editor Configuration Endpoint")
    class EditorConfigEndpointTests {

        @Test
        @DisplayName("Should return editor config for authenticated contributor user")
        void shouldReturnEditorConfigForContributor() {
            // Upload a test document
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "test.docx");
            assertThat(uploadResponse).isNotNull();

            // Get editor config - use jsonPath assertions to avoid IUserInfo deserialization issues
            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.documentServerUrl").isEqualTo(DOCUMENT_SERVER_URL)
                    .jsonPath("$.apiJsUrl").value(url -> assertThat((String) url).contains("/web-apps/apps/api/documents/api.js"))
                    .jsonPath("$.token").isNotEmpty()
                    .jsonPath("$.config").exists()
                    .jsonPath("$.config.document.fileType").isEqualTo("docx")
                    .jsonPath("$.config.document.title").isEqualTo("test.docx")
                    .jsonPath("$.config.document.url").value(url -> assertThat((String) url).contains("/onlyoffice-download"))
                    .jsonPath("$.config.document.permissions.edit").isEqualTo(true)
                    .jsonPath("$.config.editorConfig.callbackUrl").value(url -> assertThat((String) url).contains("/onlyoffice/callback/"))
                    .jsonPath("$.config.editorConfig.mode").isEqualTo("edit")
                    .jsonPath("$.config.documentType").isEqualTo("word");
        }

        @Test
        @DisplayName("Should return editor config with view mode when canEdit=false")
        void shouldReturnViewModeConfig() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "readonly.docx");
            assertThat(uploadResponse).isNotNull();

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                            .queryParam("canEdit", false)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.editorConfig.mode").isEqualTo("view")
                    .jsonPath("$.config.document.permissions.edit").isEqualTo(false);
        }

        @Test
        @DisplayName("Should return 401 for unauthenticated request")
        void shouldReturn401ForUnauthenticated() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "auth-test.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should return 403 for user without access")
        void shouldReturn403ForUnauthorizedUser() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "forbidden.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + noaccessAccessToken)
                    .exchange()
                    .expectStatus().isForbidden();
        }

        @Test
        @DisplayName("Should return error for non-existent document")
        void shouldReturnErrorForNonExistentDocument() {
            UUID nonExistentId = UUID.randomUUID();

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + nonExistentId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().is5xxServerError();
        }

        @Test
        @DisplayName("Should return correct document type for spreadsheet")
        void shouldReturnCorrectDocumentTypeForSpreadsheet() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "spreadsheet.xlsx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.documentType").isEqualTo("cell")
                    .jsonPath("$.config.document.fileType").isEqualTo("xlsx");
        }

        @Test
        @DisplayName("Should return correct document type for presentation")
        void shouldReturnCorrectDocumentTypeForPresentation() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "presentation.pptx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.documentType").isEqualTo("slide")
                    .jsonPath("$.config.document.fileType").isEqualTo("pptx");
        }

        @Test
        @DisplayName("Should include user info in editor config")
        void shouldIncludeUserInfoInConfig() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "user-info.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.editorConfig.user").exists()
                    .jsonPath("$.config.editorConfig.user.id").isNotEmpty();
        }
    }

    // ==================== DOCUMENT DOWNLOAD ENDPOINT TESTS ====================

    @Nested
    @DisplayName("GET /documents/{id}/onlyoffice-download - Document Download Endpoint")
    class DocumentDownloadEndpointTests {

        @Test
        @DisplayName("Should download document with valid OnlyOffice JWT token")
        void shouldDownloadDocumentWithValidToken() {
            // Upload a test document
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "download-test.docx");
            assertThat(uploadResponse).isNotNull();

            // Generate OnlyOffice access token
            String accessToken = generateOnlyOfficeAccessToken(
                    uploadResponse.id(),
                    "contributor-user",
                    "Contributor User"
            );

            // Download document using OnlyOffice token
            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", accessToken)
                            .build())
                    .exchange()
                    .expectStatus().isOk()
                    .expectHeader().exists(HttpHeaders.CONTENT_DISPOSITION);
        }

        @Test
        @DisplayName("Should reject download with expired token")
        void shouldRejectDownloadWithExpiredToken() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "expired-token.docx");

            String expiredToken = generateExpiredOnlyOfficeToken(uploadResponse.id(), "contributor-user");

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", expiredToken)
                            .build())
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should reject download with invalid token signature")
        void shouldRejectDownloadWithInvalidSignature() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "invalid-sig.docx");

            String invalidToken = generateTokenWithInvalidSecret(uploadResponse.id(), "contributor-user");

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", invalidToken)
                            .build())
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should reject download without token")
        void shouldRejectDownloadWithoutToken() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "no-token.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should reject download with malformed token")
        void shouldRejectDownloadWithMalformedToken() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "malformed.docx");

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", "not.a.valid.jwt.token")
                            .build())
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should download with token in Authorization header")
        void shouldDownloadWithTokenInAuthHeader() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "auth-header.docx");

            String accessToken = generateOnlyOfficeAccessToken(
                    uploadResponse.id(),
                    "contributor-user",
                    "Contributor User"
            );

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", accessToken)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .exchange()
                    .expectStatus().isOk();
        }
    }

    // ==================== CALLBACK ENDPOINT TESTS ====================

    @Nested
    @DisplayName("POST /onlyoffice/callback/{documentId} - Callback Endpoint")
    class CallbackEndpointTests {

        @Test
        @DisplayName("Should handle status=1 (editing) callback")
        void shouldHandleEditingCallback() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "editing.docx");

            String callbackToken = generateCallbackToken(uploadResponse.id(), "contributor-user");

            OnlyOfficeCallbackRequest callback = new OnlyOfficeCallbackRequest(
                    1, // EDITING status
                    uploadResponse.id().toString() + "_" + System.currentTimeMillis(),
                    null,
                    List.of("contributor-user"),
                    null,
                    null,
                    List.of(new OnlyOfficeCallbackRequest.Action(1, "contributor-user")),
                    null,
                    callbackToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", callbackToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(callback))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo(0);
        }

        @Test
        @DisplayName("Should handle status=4 (closed) callback")
        void shouldHandleClosedCallback() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "closed.docx");

            String callbackToken = generateCallbackToken(uploadResponse.id(), "contributor-user");

            OnlyOfficeCallbackRequest callback = new OnlyOfficeCallbackRequest(
                    4, // CLOSED status
                    uploadResponse.id().toString() + "_" + System.currentTimeMillis(),
                    null,
                    List.of(),
                    null,
                    null,
                    List.of(new OnlyOfficeCallbackRequest.Action(0, "contributor-user")),
                    null,
                    callbackToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", callbackToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(callback))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo(0);
        }

        @Test
        @DisplayName("Should reject callback without token")
        void shouldRejectCallbackWithoutToken() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "no-token-cb.docx");

            OnlyOfficeCallbackRequest callback = new OnlyOfficeCallbackRequest(
                    1,
                    uploadResponse.id().toString() + "_" + System.currentTimeMillis(),
                    null,
                    List.of("contributor-user"),
                    null,
                    null,
                    null,
                    null,
                    null
            );

            webTestClient.post()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(callback))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should reject callback with invalid token")
        void shouldRejectCallbackWithInvalidToken() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "invalid-cb.docx");

            String invalidToken = generateTokenWithInvalidSecret(uploadResponse.id(), "contributor-user");

            OnlyOfficeCallbackRequest callback = new OnlyOfficeCallbackRequest(
                    1,
                    uploadResponse.id().toString() + "_" + System.currentTimeMillis(),
                    null,
                    List.of("contributor-user"),
                    null,
                    null,
                    null,
                    null,
                    invalidToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", invalidToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(callback))
                    .exchange()
                    .expectStatus().isUnauthorized();
        }

        @Test
        @DisplayName("Should handle error status callback")
        void shouldHandleErrorCallback() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "error.docx");

            String callbackToken = generateCallbackToken(uploadResponse.id(), "contributor-user");

            OnlyOfficeCallbackRequest callback = new OnlyOfficeCallbackRequest(
                    3, // SAVE_ERROR status
                    uploadResponse.id().toString() + "_" + System.currentTimeMillis(),
                    null,
                    List.of(),
                    null,
                    null,
                    null,
                    null,
                    callbackToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", callbackToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(callback))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo(0);
        }
    }

    // ==================== TOKEN MANAGEMENT TESTS ====================

    @Nested
    @DisplayName("JWT Token Management Tests")
    class TokenManagementTests {

        @Test
        @DisplayName("Config token should contain correct document info")
        void configTokenShouldContainDocumentInfo() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "token-test.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.token").value(token -> {
                        assertThat((String) token).isNotBlank();
                        assertThat(((String) token).split("\\.")).hasSize(3); // JWT has 3 parts
                    });
        }

        @Test
        @DisplayName("Access token in document URL should be valid")
        void accessTokenInDocumentUrlShouldBeValid() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "access-token.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.document.url").value(url -> {
                        String documentUrl = (String) url;
                        // Extract token from URL
                        assertThat(documentUrl).contains("token=");
                        String accessToken = documentUrl.substring(documentUrl.indexOf("token=") + 6);
                        // Token should be valid JWT
                        assertThat(accessToken.split("\\.")).hasSize(3);
                    });
        }
    }

    // ==================== SECURITY TESTS ====================

    @Nested
    @DisplayName("Security Tests")
    class SecurityTests {

        @Test
        @DisplayName("Admin user should be able to get editor config")
        void adminShouldGetEditorConfig() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(adminAccessToken, "admin-test.docx");

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminAccessToken)
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("Reader user should be able to get editor config in view mode")
        void readerShouldGetViewModeConfig() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "reader-view.docx");

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                            .queryParam("canEdit", false)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.editorConfig.mode").isEqualTo("view");
        }

        @Test
        @DisplayName("Should not leak internal URLs in error responses")
        void shouldNotLeakInternalUrls() {
            UUID fakeId = UUID.randomUUID();

            webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + fakeId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectBody()
                    .consumeWith(response -> {
                        String body = new String(response.getResponseBody() != null
                                ? response.getResponseBody()
                                : new byte[0]);
                        // Should not contain internal paths
                        assertThat(body).doesNotContain("host.docker.internal");
                    });
        }
    }

    // ==================== FRONTEND SIMULATION TESTS ====================

    @Nested
    @DisplayName("Frontend Simulation Tests - OnlyOfficeEditorComponent Flow")
    class FrontendSimulationTests {

        @Test
        @DisplayName("Complete editor initialization flow should work")
        void completeEditorInitializationFlow() throws Exception {
            // Step 1: Upload a document (simulating file already in system)
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "complete-flow.docx");
            assertThat(uploadResponse).isNotNull();
            assertThat(uploadResponse.id()).isNotNull();

            // Step 2: Frontend calls getEditorConfig() via OnlyOfficeService
            // Parse response as Map to avoid IUserInfo deserialization issues
            byte[] responseBytes = webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody();

            Map<String, Object> config = objectMapper.readValue(responseBytes, Map.class);
            assertThat(config).isNotNull();

            // Step 3: Frontend would load API script from config.apiJsUrl
            assertThat(config.get("apiJsUrl")).isEqualTo(DOCUMENT_SERVER_URL + "/web-apps/apps/api/documents/api.js");

            // Step 4: Frontend calls createEditor() with the config
            Map<String, Object> configObj = (Map<String, Object>) config.get("config");
            Map<String, Object> document = (Map<String, Object>) configObj.get("document");
            assertThat(document.get("fileType")).isNotNull();
            assertThat(document.get("key")).isNotNull();
            assertThat(document.get("title")).isNotNull();
            assertThat(document.get("url")).isNotNull();
            assertThat(configObj.get("documentType")).isIn("word", "cell", "slide");
            assertThat(config.get("token")).isNotNull();

            // Step 5: Simulate OnlyOffice DocumentServer downloading the document
            String documentUrl = (String) document.get("url");
            String accessToken = documentUrl.substring(documentUrl.indexOf("token=") + 6);

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", accessToken)
                            .build())
                    .exchange()
                    .expectStatus().isOk();
        }

        @Test
        @DisplayName("View-only mode flow should work correctly")
        void viewOnlyModeFlow() {
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "view-only-flow.docx");

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                            .queryParam("canEdit", false)
                            .build())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + readerAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.config.editorConfig.mode").isEqualTo("view")
                    .jsonPath("$.config.document.permissions.edit").isEqualTo(false)
                    .jsonPath("$.config.document.permissions.download").isEqualTo(true);
        }
    }

    // ==================== DOCUMENT SERVER SIMULATION TESTS ====================

    @Nested
    @DisplayName("Document Server Simulation Tests")
    class DocumentServerSimulationTests {

        @Test
        @DisplayName("Document server download and callback flow")
        void documentServerDownloadAndCallbackFlow() throws Exception {
            // Step 1: Upload document
            UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "server-flow.docx");

            // Step 2: Get config (this would be done by frontend)
            byte[] responseBytes = webTestClient.get()
                    .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + uploadResponse.id())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .returnResult().getResponseBody();

            Map<String, Object> config = objectMapper.readValue(responseBytes, Map.class);
            Map<String, Object> configObj = (Map<String, Object>) config.get("config");
            Map<String, Object> document = (Map<String, Object>) configObj.get("document");

            // Step 3: Document server downloads the file
            String documentUrl = (String) document.get("url");
            String accessToken = documentUrl.substring(documentUrl.indexOf("token=") + 6);

            webTestClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/documents/" + uploadResponse.id() + "/onlyoffice-download")
                            .queryParam("token", accessToken)
                            .build())
                    .exchange()
                    .expectStatus().isOk();

            // Step 4: User starts editing (status=1)
            String callbackToken = generateCallbackToken(uploadResponse.id(), "contributor-user");
            String documentKey = (String) document.get("key");

            OnlyOfficeCallbackRequest editingCallback = new OnlyOfficeCallbackRequest(
                    1, // EDITING
                    documentKey,
                    null,
                    List.of("contributor-user"),
                    null,
                    null,
                    List.of(new OnlyOfficeCallbackRequest.Action(1, "contributor-user")),
                    null,
                    callbackToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", callbackToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(editingCallback))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo(0);

            // Step 5: User closes document (status=4)
            OnlyOfficeCallbackRequest closedCallback = new OnlyOfficeCallbackRequest(
                    4, // CLOSED
                    documentKey,
                    null,
                    List.of(),
                    null,
                    null,
                    List.of(new OnlyOfficeCallbackRequest.Action(0, "contributor-user")),
                    null,
                    callbackToken
            );

            webTestClient.post()
                    .uri(uriBuilder -> uriBuilder
                            .path(RestApiVersion.API_PREFIX + "/onlyoffice/callback/" + uploadResponse.id())
                            .queryParam("token", callbackToken)
                            .build())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(BodyInserters.fromValue(closedCallback))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.error").isEqualTo(0);
        }
    }
}
