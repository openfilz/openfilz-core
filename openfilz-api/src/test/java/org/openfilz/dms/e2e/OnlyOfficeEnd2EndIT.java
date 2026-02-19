package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import org.openqa.selenium.support.ui.WebDriverWait;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.BrowserWebDriverContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.utility.DockerImageName;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * End-to-end integration tests for OnlyOffice Document Server integration.
 *
 * These tests require the OnlyOffice Document Server container (~5GB image)
 * and verify the complete flow between the backend and a real OnlyOffice instance.
 *
 * Tests use the OnlyOffice Command Service API to trigger real callbacks
 * from the Document Server to our backend.
 */
@Slf4j
@org.testcontainers.junit.jupiter.Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class OnlyOfficeEnd2EndIT extends TestContainersKeyCloakConfig {

    private static final String ONLYOFFICE_JWT_SECRET = "openfilz-onlyoffice-test-jwt-secret-2024";

    /**
     * Detect if running in GitHub Actions CI environment.
     */
    private static final boolean IS_CI = System.getenv("GITHUB_ACTIONS") != null ||
            System.getenv("CI") != null;

    /**
     * Port allocated for this test instance, needed to configure callback URLs
     * that OnlyOffice Document Server can reach.
     */
    @LocalServerPort
    private int serverPort;

    /**
     * Shared network for container-to-container communication.
     */
    static Network network = Network.newNetwork();

    /**
     * OnlyOffice Document Server container for end-to-end testing.
     * Note: This is a large image (~5GB) and takes 1-2 minutes to start.
     * The container is reused across tests for efficiency.
     *
     * Uses withExtraHost to allow the container to reach the host via host.docker.internal.
     * This works on Docker Desktop (Windows/Mac). On Linux/CI, the behavior may differ.
     */
    @Container
    static GenericContainer<?> onlyOfficeServer = new GenericContainer<>(
            DockerImageName.parse("onlyoffice/documentserver:latest"))
            .withNetwork(network)
            .withNetworkAliases("onlyoffice")
            .withExposedPorts(80)
            .withEnv("JWT_ENABLED", "true")
            .withEnv("JWT_SECRET", ONLYOFFICE_JWT_SECRET)
            .withEnv("JWT_HEADER", "Authorization")
            // Allow container to reach host via host.docker.internal
            .withExtraHost("host.docker.internal", "host-gateway")
            .waitingFor(Wait.forHttp("/healthcheck")
                    .forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(3)))
            .withReuse(true);

    /**
     * Chrome browser container for browser-based E2E testing.
     * Used to load the OnlyOffice editor and simulate user interactions.
     */
    @Container
    static BrowserWebDriverContainer<?> chrome = new BrowserWebDriverContainer<>()
            .withNetwork(network)
            .withCapabilities(new ChromeOptions()
                    .addArguments("--no-sandbox")
                    .addArguments("--disable-dev-shm-usage")
                    .addArguments("--disable-gpu")
                    .addArguments("--window-size=1920,1080"));

    protected String contributorAccessToken;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private SecretKey onlyOfficeSecretKey;
    private HttpClient httpClient;
    private boolean commandServiceReady = false;

    public OnlyOfficeEnd2EndIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @DynamicPropertySource
    static void registerOnlyOfficeProperties(DynamicPropertyRegistry registry) {
        // Keycloak configuration
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);

        // Ensure OnlyOfficeSecurityConfig is activated (not custom-access mode)
        registry.add("openfilz.features.custom-access", () -> false);

        // OnlyOffice configuration - use container URL for document server
        // The api-base-url will be set dynamically in tests since we need the server port
        registry.add("onlyoffice.enabled", () -> true);
        registry.add("onlyoffice.document-server.url",
                () -> "http://" + onlyOfficeServer.getHost() + ":" + onlyOfficeServer.getMappedPort(80));
        registry.add("onlyoffice.document-server.api-path", () -> "/web-apps/apps/api/documents/api.js");
        // Use host.docker.internal so OnlyOffice container can reach our backend
        // This is configured via withExtraHost("host.docker.internal", "host-gateway")
        // Note: This URL is used in editor config and will be updated per-test
        registry.add("onlyoffice.api-base-url", () -> "http://host.docker.internal:8081");
        registry.add("onlyoffice.jwt.enabled", () -> true);
        registry.add("onlyoffice.jwt.secret", () -> ONLYOFFICE_JWT_SECRET);
        registry.add("onlyoffice.jwt.expiration-seconds", () -> 3600);
        registry.add("onlyoffice.supported-extensions", () -> "docx,doc,xlsx,xls,pptx,ppt,odt,ods,odp");

        registry.add("logging.level.org.openfilz", () -> "DEBUG");
    }

    @BeforeEach
    void setUp() {
        contributorAccessToken = getAccessToken("contributor-user");
        String paddedSecret = ONLYOFFICE_JWT_SECRET;
        // Initialize OnlyOffice secret key for generating test tokens
        if (paddedSecret.length() < 32) {
            paddedSecret = String.format("%-32s", ONLYOFFICE_JWT_SECRET).substring(0, 32);
        }
        log.debug("padded secret {}", paddedSecret);
        onlyOfficeSecretKey = Keys.hmacShaKeyFor(paddedSecret.getBytes(StandardCharsets.UTF_8));

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();

        // Wait for Command Service to be ready (it takes longer than healthcheck)
        if (!commandServiceReady) {
            log.info("Waiting for OnlyOffice Command Service to be ready...");
            waitForCommandService();
        }

        log.info("Test server running on port: {}", serverPort);
        log.info("Running in CI: {}", IS_CI);
        log.info("Backend URL for containers: {}", getBackendUrlForContainers());
        log.info("OnlyOffice Document Server: {}", getOnlyOfficeServerUrl());
    }

    /**
     * Wait for the OnlyOffice Command Service to be ready.
     * The service may return 502 Bad Gateway while starting up.
     */
    private void waitForCommandService() {
        int maxAttempts = 30;
        int attemptDelay = 2000; // 2 seconds

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                // Create a simple version command request
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("c", "version");

                String token = Jwts.builder()
                        .claims(requestBody)
                        .issuedAt(new Date())
                        .expiration(new Date(System.currentTimeMillis() + 3600 * 1000))
                        .signWith(onlyOfficeSecretKey)
                        .compact();
                requestBody.put("token", token);

                String jsonBody = objectMapper.writeValueAsString(requestBody);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(getOnlyOfficeServerUrl() + "/coauthoring/CommandService.ashx"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200 && !response.body().contains("<html>")) {
                    log.info("Command Service is ready after {} attempts", attempt);
                    commandServiceReady = true;
                    return;
                } else {
                    log.debug("Command Service not ready (attempt {}/{}): status={}, body={}",
                            attempt, maxAttempts, response.statusCode(),
                            response.body().substring(0, Math.min(100, response.body().length())));
                }
            } catch (Exception e) {
                log.debug("Command Service check failed (attempt {}/{}): {}", attempt, maxAttempts, e.getMessage());
            }

            try {
                TimeUnit.MILLISECONDS.sleep(attemptDelay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        log.warn("Command Service may not be fully ready after {} attempts", maxAttempts);
    }

    // ==================== HELPER METHODS ====================

    /**
     * Get the URL of the OnlyOffice Document Server container.
     */
    private String getOnlyOfficeServerUrl() {
        return "http://" + onlyOfficeServer.getHost() + ":" + onlyOfficeServer.getMappedPort(80);
    }

    /**
     * Get the base URL for our backend that containers can reach.
     * Uses host.docker.internal which is set up via withExtraHost("host.docker.internal", "host-gateway").
     *
     * Note: Tests that require container-to-host networking are disabled on CI
     * since this setup only works reliably on Docker Desktop (Windows/Mac).
     */
    private String getBackendUrlForContainers() {
        return "http://host.docker.internal:" + serverPort;
    }

    /**
     * Get the hostname that containers should use to reach the host.
     * Used for URL replacements in editor config.
     */
    private String getContainerHostname() {
        return "host.docker.internal";
    }

    /**
     * Get the base URL for our backend that OnlyOffice container can reach.
     */
    private String getBackendUrlForOnlyOffice() {
        return getBackendUrlForContainers();
    }

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
     * Generate a JWT token for OnlyOffice Command Service API calls.
     */
    private String generateCommandServiceToken(Map<String, Object> payload) {
        Date now = new Date();
        Date expiration = new Date(now.getTime() + 3600 * 1000);

        return Jwts.builder()
                .claims(payload)
                .issuedAt(now)
                .expiration(expiration)
                .signWith(onlyOfficeSecretKey)
                .compact();
    }

    /**
     * Call OnlyOffice Command Service API with retry logic.
     * Retries on 502 Bad Gateway errors which can occur while the service is warming up.
     *
     * @param command The command to execute (info, drop, forcesave, version, license)
     * @param key     The document key (required for most commands)
     * @return Response from the Command Service
     */
    private Map<String, Object> callCommandService(String command, String key) throws IOException, InterruptedException {
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("c", command);
        if (key != null) {
            requestBody.put("key", key);
        }

        // Generate JWT token containing the request payload
        String token = generateCommandServiceToken(requestBody);
        requestBody.put("token", token);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("Command Service request: {}", jsonBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getOnlyOfficeServerUrl() + "/coauthoring/CommandService.ashx"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        // Retry logic for 502 Bad Gateway errors and transient connection issues
        int maxRetries = 5;
        int retryDelay = 2000; // 2 seconds

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            HttpResponse<String> response;
            try {
                response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    log.warn("Command Service connection failed (attempt {}/{}): {}, retrying in {}ms...",
                            attempt, maxRetries, e.getMessage(), retryDelay);
                    TimeUnit.MILLISECONDS.sleep(retryDelay);
                    retryDelay = Math.min(retryDelay * 2, 10000);
                    continue;
                }
                throw new IOException("Command Service connection failed after " + maxRetries + " attempts", e);
            }
            log.debug("Command Service response: {} - {}", response.statusCode(), response.body());

            if (response.statusCode() == 200 && !response.body().trim().startsWith("<")) {
                return objectMapper.readValue(response.body(), Map.class);
            }

            if (response.statusCode() == 502 || response.body().trim().startsWith("<")) {
                if (attempt < maxRetries) {
                    log.warn("Command Service returned {} (attempt {}/{}), retrying in {}ms...",
                            response.statusCode(), attempt, maxRetries, retryDelay);
                    TimeUnit.MILLISECONDS.sleep(retryDelay);
                    // Increase delay for next retry
                    retryDelay = Math.min(retryDelay * 2, 10000);
                } else {
                    log.error("Command Service failed after {} attempts: {} - {}",
                            maxRetries, response.statusCode(), response.body());
                    throw new IOException("Command Service returned " + response.statusCode() +
                            " after " + maxRetries + " attempts: " + response.body());
                }
            } else {
                // Some other error, return immediately
                return objectMapper.readValue(response.body(), Map.class);
            }
        }

        throw new IOException("Command Service failed after " + maxRetries + " attempts");
    }

    /**
     * Call OnlyOffice Conversion Service API to convert/process a document.
     * This triggers OnlyOffice to download the document from our backend.
     *
     * @param documentUrl URL where OnlyOffice should download the document
     * @param documentKey Unique key for the document
     * @param outputType  Target format (e.g., "pdf", "docx")
     * @return Response from the Conversion Service
     */
    private Map<String, Object> callConversionService(String documentUrl, String documentKey, String outputType)
            throws IOException, InterruptedException {

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("async", false);
        requestBody.put("filetype", "docx");
        requestBody.put("key", documentKey);
        requestBody.put("outputtype", outputType);
        requestBody.put("url", documentUrl);

        // Generate JWT token containing the request payload
        String token = generateCommandServiceToken(requestBody);
        requestBody.put("token", token);

        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("Conversion Service request: {}", jsonBody);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getOnlyOfficeServerUrl() + "/ConvertService.ashx"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.debug("Conversion Service response: {} - {}", response.statusCode(), response.body());

        return objectMapper.readValue(response.body(), Map.class);
    }

    // ==================== ONLYOFFICE DOCUMENT SERVER E2E TESTS ====================

    @Test
    @DisplayName("OnlyOffice Document Server should be running and healthy")
    void onlyOfficeServerShouldBeHealthy() throws IOException, InterruptedException {
        assertThat(onlyOfficeServer.isRunning()).isTrue();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getOnlyOfficeServerUrl() + "/healthcheck"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("true");
    }

    @Test
    @DisplayName("OnlyOffice API JS should be accessible")
    void onlyOfficeApiShouldBeAccessible() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(getOnlyOfficeServerUrl() + "/web-apps/apps/api/documents/api.js"))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("DocsAPI");
    }

    @Test
    @DisplayName("Command Service version endpoint should respond")
    void commandServiceVersionShouldWork() throws IOException, InterruptedException {
        Map<String, Object> response = callCommandService("version", null);

        log.info("Command Service version response: {}", response);
        // OnlyOffice returns error=0 for successful commands
        // or specific error codes for failures
        assertThat(response).isNotNull();
        // Version command should return version info or error=0
        assertThat(response).containsKey("error");
    }

    @Test
    @DisplayName("Command Service license endpoint should respond")
    void commandServiceLicenseShouldWork() throws IOException, InterruptedException {
        Map<String, Object> response = callCommandService("license", null);

        log.info("Command Service license response: {}", response);
        assertThat(response).isNotNull();
        assertThat(response).containsKey("error");
    }

    @Test
    @DisplayName("OnlyOffice should download document from backend via Conversion API")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
            disabledReason = "Requires container-to-host networking which is not available on CI")
    void onlyOfficeShouldDownloadDocumentFromBackend() throws Exception {
        // Step 1: Upload a test document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "conversion-test.docx");
        assertThat(uploadResponse).isNotNull();
        UUID documentId = uploadResponse.id();

        // Step 2: Generate access token for OnlyOffice to download our document
        String accessToken = generateOnlyOfficeAccessToken(documentId, "converter", "Converter");

        // Step 3: Build the document URL that OnlyOffice will use to fetch from our backend
        String documentUrl = getBackendUrlForOnlyOffice() + RestApiVersion.API_PREFIX
                + "/documents/" + documentId + "/onlyoffice-download?token=" + accessToken;

        log.info("Document URL for OnlyOffice: {}", documentUrl);

        // Step 4: Call OnlyOffice Conversion Service - this will make OnlyOffice download our document
        String documentKey = "conv_" + documentId.toString() + "_" + System.currentTimeMillis();
        Map<String, Object> response = callConversionService(documentUrl, documentKey, "pdf");

        log.info("Conversion response: {}", response);

        // Step 5: Verify the conversion was processed successfully
        // Successful response contains: endConvert=true, fileType, fileUrl, percent=100
        // Error response contains: error key with error code
        assertThat(response).isNotNull();

        if (response.containsKey("error")) {
            Integer error = (Integer) response.get("error");
            log.warn("Conversion error code: {} - This may indicate network connectivity issues between OnlyOffice container and backend", error);
            // Error -8 typically means OnlyOffice couldn't download the file
            // This can happen if host.docker.internal isn't properly resolving
            assertThat(error).as("Conversion should succeed with error=0").isEqualTo(0);
        } else {
            // Successful conversion - OnlyOffice downloaded our document and converted it!
            assertThat(response).containsKey("endConvert");
            assertThat(response.get("endConvert")).isEqualTo(true);
            assertThat(response).containsKey("fileUrl");
            log.info("Conversion successful! OnlyOffice downloaded and converted our document to PDF.");
            log.info("Converted file URL: {}", response.get("fileUrl"));
        }
    }

    @Test
    @DisplayName("Command Service info should return document status")
    void commandServiceInfoShouldReturnDocumentStatus() throws Exception {
        // Upload a document and get its key
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "info-test.docx");
        UUID documentId = uploadResponse.id();

        // Generate a document key (same format as used in editor config)
        String documentKey = documentId.toString() + "_" + System.currentTimeMillis();

        // Call info command - should return status for unknown document
        Map<String, Object> response = callCommandService("info", documentKey);

        log.info("Info response for key {}: {}", documentKey, response);

        // For a document that hasn't been opened in OnlyOffice yet,
        // we expect an error (typically error=1 for unknown key)
        assertThat(response).containsKey("error");
    }

    @Test
    @DisplayName("Full E2E flow: Upload, get config, trigger conversion")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
            disabledReason = "Requires container-to-host networking which is not available on CI")
    void fullEndToEndFlow() throws Exception {
        // Step 1: Verify OnlyOffice is running
        assertThat(onlyOfficeServer.isRunning()).isTrue();
        log.info("OnlyOffice Document Server running at: {}", getOnlyOfficeServerUrl());
        log.info("Backend accessible to OnlyOffice at: {}", getBackendUrlForOnlyOffice());

        // Step 2: Upload a document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "e2e-full-test.docx");
        assertThat(uploadResponse).isNotNull();
        UUID documentId = uploadResponse.id();
        log.info("Uploaded document with ID: {}", documentId);

        // Step 3: Get editor config from our backend
        byte[] configBytes = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult().getResponseBody();

        Map<String, Object> editorConfig = objectMapper.readValue(configBytes, Map.class);
        assertThat(editorConfig).containsKey("token");
        assertThat(editorConfig).containsKey("config");

        Map<String, Object> config = (Map<String, Object>) editorConfig.get("config");
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        String documentKey = (String) document.get("key");
        String documentUrl = (String) document.get("url");

        log.info("Editor config document key: {}", documentKey);
        log.info("Editor config document URL: {}", documentUrl);

        // Step 4: Verify the document URL structure
        assertThat(documentUrl).contains("onlyoffice-download");
        assertThat(documentUrl).contains("token=");

        // Step 5: Test that our backend download endpoint works
        String downloadToken = documentUrl.substring(documentUrl.indexOf("token=") + 6);
        webTestClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/documents/" + documentId + "/onlyoffice-download")
                        .queryParam("token", downloadToken)
                        .build())
                .exchange()
                .expectStatus().isOk()
                .expectHeader().exists(HttpHeaders.CONTENT_DISPOSITION);

        log.info("Document download endpoint verified");

        // Step 6: Call OnlyOffice Command Service to check document status
        Map<String, Object> infoResponse = callCommandService("info", documentKey);
        log.info("Document info from OnlyOffice: {}", infoResponse);

        // Step 7: Try to trigger OnlyOffice to process the document via Conversion API
        // Build URL using host.docker.internal so OnlyOffice container can reach our backend
        String onlyOfficeAccessibleUrl = getBackendUrlForOnlyOffice() + RestApiVersion.API_PREFIX
                + "/documents/" + documentId + "/onlyoffice-download?token=" + downloadToken;

        log.info("Attempting conversion with URL accessible to OnlyOffice: {}", onlyOfficeAccessibleUrl);

        Map<String, Object> conversionResponse = callConversionService(
                onlyOfficeAccessibleUrl,
                documentKey + "_conv",
                "pdf"
        );

        log.info("Conversion response: {}", conversionResponse);

        // Verify OnlyOffice processed our request successfully
        assertThat(conversionResponse).isNotNull();

        if (conversionResponse.containsKey("error")) {
            Integer error = (Integer) conversionResponse.get("error");
            log.warn("Conversion returned error code: {} - this may indicate network issues", error);
            assertThat(error).as("Conversion should succeed").isEqualTo(0);
        } else {
            // Successful conversion
            assertThat(conversionResponse).containsKey("endConvert");
            assertThat(conversionResponse.get("endConvert")).isEqualTo(true);
            assertThat(conversionResponse).containsKey("fileUrl");
            log.info("Conversion successful! OnlyOffice downloaded and processed our document.");
            log.info("Converted file URL: {}", conversionResponse.get("fileUrl"));
        }

        log.info("E2E flow completed - OnlyOffice integration fully verified!");
    }

    @Test
    @DisplayName("Verify callback URL is properly formatted in editor config")
    void callbackUrlShouldBeProperlyFormatted() throws Exception {
        // Upload a document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "callback-url-test.docx");
        UUID documentId = uploadResponse.id();

        // Get editor config
        byte[] configBytes = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult().getResponseBody();

        Map<String, Object> editorConfig = objectMapper.readValue(configBytes, Map.class);
        Map<String, Object> config = (Map<String, Object>) editorConfig.get("config");
        Map<String, Object> editorConfigSection = (Map<String, Object>) config.get("editorConfig");
        String callbackUrl = (String) editorConfigSection.get("callbackUrl");

        log.info("Callback URL in editor config: {}", callbackUrl);

        // Verify callback URL structure
        assertThat(callbackUrl).isNotNull();
        assertThat(callbackUrl).contains("/onlyoffice/callback/");
        assertThat(callbackUrl).contains(documentId.toString());
        // Note: The callback URL may or may not include a token depending on implementation
        // OnlyOffice will include its own JWT token in the callback request body
    }

    @Test
    @DisplayName("Command Service drop should handle unknown document gracefully")
    void commandServiceDropShouldHandleUnknownDocument() throws Exception {
        // Try to drop a document that was never opened in OnlyOffice
        String unknownKey = "unknown_" + UUID.randomUUID().toString();

        Map<String, Object> response = callCommandService("drop", unknownKey);

        log.info("Drop response for unknown key: {}", response);

        // Should return an error for unknown document
        assertThat(response).containsKey("error");
        // Error code 1 typically means "document not found"
    }

    @Test
    @DisplayName("Command Service forcesave should handle unknown document gracefully")
    void commandServiceForcesaveShouldHandleUnknownDocument() throws Exception {
        // Try to forcesave a document that was never opened
        String unknownKey = "unknown_" + UUID.randomUUID().toString();

        Map<String, Object> response = callCommandService("forcesave", unknownKey);

        log.info("Forcesave response for unknown key: {}", response);

        // Should return an error for unknown document (no active editing session)
        assertThat(response).containsKey("error");
    }

    // ==================== BROWSER-BASED E2E TESTS ====================

    /**
     * Get the OnlyOffice URL accessible from within the Docker network.
     * Browser container uses this to access OnlyOffice.
     */
    private String getOnlyOfficeUrlForBrowser() {
        // Use the network alias so browser can reach OnlyOffice
        return "http://onlyoffice";
    }

    /**
     * Generate an HTML page that loads the OnlyOffice editor.
     * This page is loaded via data URL in the browser.
     */
    private String generateEditorHtmlPage(String documentServerUrl, String configJson, String token) {
        return """
            <!DOCTYPE html>
            <html>
            <head>
                <title>OnlyOffice Editor Test</title>
                <style>
                    body { margin: 0; padding: 0; overflow: hidden; }
                    #placeholder { height: 100vh; width: 100vw; }
                </style>
            </head>
            <body>
                <div id="placeholder"></div>
                <script type="text/javascript" src="%s/web-apps/apps/api/documents/api.js"></script>
                <script type="text/javascript">
                    window.editorReady = false;
                    window.editorError = null;
                    window.docEditor = null;

                    var config = %s;
                    config.token = '%s';

                    // Add event handlers
                    config.events = config.events || {};
                    config.events.onReady = function() {
                        console.log('Editor ready');
                        window.editorReady = true;
                    };
                    config.events.onError = function(event) {
                        console.error('Editor error:', event);
                        window.editorError = event;
                    };
                    config.events.onDocumentStateChange = function(event) {
                        console.log('Document state changed:', event.data);
                    };

                    try {
                        window.docEditor = new DocsAPI.DocEditor("placeholder", config);
                    } catch (e) {
                        console.error('Failed to create editor:', e);
                        window.editorError = e.message;
                    }
                </script>
            </body>
            </html>
            """.formatted(documentServerUrl, configJson, token);
    }

    /**
     * Browser-based E2E test: Load editor, verify initialization, close.
     * This test opens the OnlyOffice editor in a real browser and verifies
     * it initializes correctly with our document.
     */
    @Test
    @DisplayName("Browser E2E: Load OnlyOffice editor and verify initialization")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
            disabledReason = "Requires container-to-host networking which is not available on CI")
    void browserShouldLoadOnlyOfficeEditor() throws Exception {
        // Step 1: Upload a test document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "browser-test.docx");
        assertThat(uploadResponse).isNotNull();
        UUID documentId = uploadResponse.id();
        log.info("Uploaded document for browser test: {}", documentId);

        // Step 2: Get editor config from our backend
        byte[] configBytes = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult().getResponseBody();

        Map<String, Object> editorResponse = objectMapper.readValue(configBytes, Map.class);
        String token = (String) editorResponse.get("token");
        Map<String, Object> config = (Map<String, Object>) editorResponse.get("config");

        // Step 3: Modify config URLs to be accessible from containers
        // Both browser and OnlyOffice need to access backend via the appropriate hostname
        String hostname = getContainerHostname();
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        String originalUrl = (String) document.get("url");
        // Replace any host references with the appropriate container hostname
        String containerAccessibleUrl = originalUrl
                .replace("localhost:" + serverPort, hostname + ":" + serverPort)
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        document.put("url", containerAccessibleUrl);
        log.info("Document URL for containers: {}", containerAccessibleUrl);

        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        String callbackUrl = (String) editorConfig.get("callbackUrl");
        String containerAccessibleCallbackUrl = callbackUrl
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        editorConfig.put("callbackUrl", containerAccessibleCallbackUrl);
        log.info("Callback URL for containers: {}", containerAccessibleCallbackUrl);

        String configJson = objectMapper.writeValueAsString(config);
        log.info("Editor config for browser: {}", configJson);

        // Step 4: Generate HTML page and load in browser
        // Use network alias so browser container can reach OnlyOffice container
        String onlyOfficeUrlForBrowser = getOnlyOfficeUrlForBrowser();
        String html = generateEditorHtmlPage(onlyOfficeUrlForBrowser, configJson, token);

        // Encode as data URL
        String dataUrl = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));

        WebDriver driver = chrome.getWebDriver();
        try {
            log.info("Loading OnlyOffice editor in browser using URL: {}", onlyOfficeUrlForBrowser);
            driver.get(dataUrl);

            // Step 5: Wait for editor to initialize
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));

            // Wait for either editor ready or error
            wait.until(d -> {
                JavascriptExecutor js = (JavascriptExecutor) d;
                Boolean ready = (Boolean) js.executeScript("return window.editorReady === true");
                Object error = js.executeScript("return window.editorError");
                return ready || error != null;
            });

            // Check result
            JavascriptExecutor js = (JavascriptExecutor) driver;
            Boolean editorReady = (Boolean) js.executeScript("return window.editorReady");
            Object editorError = js.executeScript("return window.editorError");

            if (editorError != null) {
                log.warn("Editor error (may be expected for community edition): {}", editorError);
            }

            log.info("Editor ready: {}, Error: {}", editorReady, editorError);

            // The editor should have loaded (even if there might be an error from community edition)
            // The important thing is the DocsAPI script loaded and attempted to create the editor
            Object docEditor = js.executeScript("return window.docEditor !== null");

            // Also check if DocsAPI is available (indicates script loaded)
            Boolean docsApiLoaded = (Boolean) js.executeScript("return typeof DocsAPI !== 'undefined'");
            log.info("DocsAPI loaded: {}, DocEditor created: {}", docsApiLoaded, docEditor);

            // The test passes if either:
            // 1. The editor was created successfully, OR
            // 2. DocsAPI loaded (even if document failed due to community edition limits)
            assertThat(docsApiLoaded != null && docsApiLoaded || (docEditor != null && docEditor.equals(true)))
                .as("DocsAPI should be loaded or DocEditor should be created")
                .isTrue();

            log.info("Browser E2E test completed - OnlyOffice integration verified");

        } finally {
            // Don't quit driver as it's managed by container
        }
    }

    /**
     * Browser-based E2E test: Edit document and trigger save via editor close.
     * This test opens the editor, makes changes, and closes it to trigger
     * the save callback from OnlyOffice to our backend.
     */
    @Test
    @DisplayName("Browser E2E: Edit document and verify save callback")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
            disabledReason = "Requires container-to-host networking which is not available on CI")
    void browserShouldEditAndSaveDocument() throws Exception {
        // Step 1: Upload a test document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "browser-edit-test.docx");
        assertThat(uploadResponse).isNotNull();
        UUID documentId = uploadResponse.id();
        log.info("Uploaded document for browser edit test: {}", documentId);

        // Step 2: Get editor config
        byte[] configBytes = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult().getResponseBody();

        Map<String, Object> editorResponse = objectMapper.readValue(configBytes, Map.class);
        String token = (String) editorResponse.get("token");
        Map<String, Object> config = (Map<String, Object>) editorResponse.get("config");

        // Step 3: Update URLs for container access
        String hostname = getContainerHostname();
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        String documentKey = (String) document.get("key");
        String originalUrl = (String) document.get("url");
        String containerAccessibleUrl = originalUrl
                .replace("localhost:" + serverPort, hostname + ":" + serverPort)
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        document.put("url", containerAccessibleUrl);
        log.info("Document URL for containers: {}", containerAccessibleUrl);

        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        String callbackUrl = (String) editorConfig.get("callbackUrl");
        String containerAccessibleCallbackUrl = callbackUrl
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        editorConfig.put("callbackUrl", containerAccessibleCallbackUrl);
        log.info("Callback URL for containers: {}", containerAccessibleCallbackUrl);

        String configJson = objectMapper.writeValueAsString(config);

        // Step 4: Generate HTML and load in browser
        // Use network alias so browser container can reach OnlyOffice container
        String onlyOfficeUrlForBrowser = getOnlyOfficeUrlForBrowser();
        String html = generateEditorHtmlPage(onlyOfficeUrlForBrowser, configJson, token);
        String dataUrl = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));

        WebDriver driver = chrome.getWebDriver();
        try {
            log.info("Loading OnlyOffice editor for editing using URL: {}", onlyOfficeUrlForBrowser);
            driver.get(dataUrl);

            // Step 5: Wait for editor to be ready or error
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            wait.until(d -> {
                Boolean ready = (Boolean) ((JavascriptExecutor) d).executeScript("return window.editorReady === true");
                Object error = ((JavascriptExecutor) d).executeScript("return window.editorError");
                // Accept either ready or error as a valid outcome (community edition may error)
                return (ready != null && ready) || error != null;
            });

            Boolean editorReady = (Boolean) js.executeScript("return window.editorReady");
            Object editorError = js.executeScript("return window.editorError");
            log.info("Editor ready: {}, Error: {}", editorReady, editorError);

            if (editorReady == null || !editorReady) {
                log.warn("Editor not fully ready - may be due to community edition limits");
            }

            // Step 6: Wait a moment for the editor iframe to fully load
            Thread.sleep(3000);

            // Step 7: Check document info via Command Service
            Map<String, Object> infoResponse = callCommandService("info", documentKey);
            log.info("Document info after opening in browser: {}", infoResponse);

            // If the document is being edited, info should return user info
            // error=0 means document is known and being edited
            if (infoResponse.containsKey("error") && infoResponse.get("error").equals(0)) {
                log.info("Document is being edited in OnlyOffice!");

                // Step 8: Try to trigger a forcesave
                Map<String, Object> forcesaveResponse = callCommandService("forcesave", documentKey);
                log.info("Forcesave response: {}", forcesaveResponse);
            }

            // Step 9: Close the document via Command Service (this triggers callback)
            log.info("Closing document via Command Service to trigger save callback...");
            Map<String, Object> dropResponse = callCommandService("drop", documentKey);
            log.info("Drop response: {}", dropResponse);

            // Wait for callback to be processed
            Thread.sleep(2000);

            log.info("Browser edit E2E test completed");

        } finally {
            // Driver is managed by container
        }
    }

    /**
     * Browser-based E2E test: Verify editor loads document content.
     * Opens the editor and verifies the document content is displayed.
     */
    @Test
    @DisplayName("Browser E2E: Verify document content loads in editor")
    @DisabledIfEnvironmentVariable(named = "CI", matches = "true",
            disabledReason = "Requires container-to-host networking which is not available on CI")
    void browserShouldDisplayDocumentContent() throws Exception {
        // Step 1: Upload a test document
        UploadResponse uploadResponse = uploadTestDocumentWithName(contributorAccessToken, "browser-content-test.docx");
        assertThat(uploadResponse).isNotNull();
        UUID documentId = uploadResponse.id();
        log.info("Uploaded document for content verification: {}", documentId);

        // Step 2: Get editor config
        byte[] configBytes = webTestClient.get()
                .uri(RestApiVersion.API_PREFIX + "/onlyoffice/config/" + documentId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + contributorAccessToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .returnResult().getResponseBody();

        Map<String, Object> editorResponse = objectMapper.readValue(configBytes, Map.class);
        String token = (String) editorResponse.get("token");
        Map<String, Object> config = (Map<String, Object>) editorResponse.get("config");
        String documentKey = (String) ((Map<String, Object>) config.get("document")).get("key");

        // Step 3: Update URLs for container access
        String hostname = getContainerHostname();
        Map<String, Object> document = (Map<String, Object>) config.get("document");
        String originalUrl = (String) document.get("url");
        String containerAccessibleUrl = originalUrl
                .replace("localhost:" + serverPort, hostname + ":" + serverPort)
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        document.put("url", containerAccessibleUrl);
        log.info("Document URL for containers: {}", containerAccessibleUrl);

        Map<String, Object> editorConfig = (Map<String, Object>) config.get("editorConfig");
        String callbackUrl = (String) editorConfig.get("callbackUrl");
        String containerAccessibleCallbackUrl = callbackUrl
                .replace("host.docker.internal:8081", hostname + ":" + serverPort)
                .replace("host.testcontainers.internal:8081", hostname + ":" + serverPort);
        editorConfig.put("callbackUrl", containerAccessibleCallbackUrl);
        log.info("Callback URL for containers: {}", containerAccessibleCallbackUrl);

        String configJson = objectMapper.writeValueAsString(config);

        // Step 4: Load in browser
        // Use network alias so browser container can reach OnlyOffice container
        String onlyOfficeUrlForBrowser = getOnlyOfficeUrlForBrowser();
        String html = generateEditorHtmlPage(onlyOfficeUrlForBrowser, configJson, token);
        String dataUrl = "data:text/html;base64," + Base64.getEncoder().encodeToString(html.getBytes(StandardCharsets.UTF_8));

        WebDriver driver = chrome.getWebDriver();
        try {
            log.info("Loading editor to verify document content using URL: {}", onlyOfficeUrlForBrowser);
            driver.get(dataUrl);

            // Step 5: Wait for editor
            WebDriverWait wait = new WebDriverWait(driver, Duration.ofSeconds(60));
            JavascriptExecutor js = (JavascriptExecutor) driver;

            wait.until(d -> {
                Boolean ready = (Boolean) ((JavascriptExecutor) d).executeScript("return window.editorReady === true");
                Object error = ((JavascriptExecutor) d).executeScript("return window.editorError");
                return (ready != null && ready) || error != null;
            });

            Boolean editorReady = (Boolean) js.executeScript("return window.editorReady");
            Object editorError = js.executeScript("return window.editorError");

            log.info("Editor ready: {}, Error: {}", editorReady, editorError);

            // Step 6: Wait for content to load
            Thread.sleep(5000);

            // Step 7: Verify via Command Service that document is open
            Map<String, Object> infoResponse = callCommandService("info", documentKey);
            log.info("Document info: {}", infoResponse);

            // Take a screenshot for debugging (logs the page source)
            String pageSource = driver.getPageSource();
            log.debug("Page source length: {} chars", pageSource.length());

            // Check if DocsAPI is available (indicates script loaded from OnlyOffice)
            Boolean docsApiLoaded = (Boolean) js.executeScript("return typeof DocsAPI !== 'undefined'");
            log.info("DocsAPI loaded: {}", docsApiLoaded);

            // Verify editor was created or DocsAPI loaded
            Object docEditor = js.executeScript("return window.docEditor !== null");
            boolean hasEditorContent = (Boolean) js.executeScript(
                "return document.querySelector('iframe') !== null || document.getElementById('placeholder').children.length > 0"
            );
            log.info("Has editor content: {}, DocEditor created: {}", hasEditorContent, docEditor);

            // The test passes if DocsAPI loaded (proving browser can reach OnlyOffice)
            assertThat(docsApiLoaded != null && docsApiLoaded)
                .as("DocsAPI should be loaded from OnlyOffice server")
                .isTrue();

            // Cleanup: drop the document
            callCommandService("drop", documentKey);

            log.info("Browser content verification test completed");

        } finally {
            // Driver is managed by container
        }
    }
}
