package org.openfilz.dms.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.UserQuotaRequest;
import org.openfilz.dms.dto.response.DashboardStatisticsResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.dto.response.quota.MyStorageQuota;
import org.openfilz.dms.dto.response.quota.QuotaOverview;
import org.openfilz.dms.dto.response.quota.QuotaSource;
import org.openfilz.dms.dto.response.quota.QuotaUsage;
import org.openfilz.dms.dto.response.quota.QuotaUserPage;
import org.openfilz.dms.exception.GlobalExceptionHandler.ErrorResponse;
import org.openfilz.dms.exception.OpenFilzException;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.JacksonJsonEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * Per-user storage quota overrides, end to end through the REST API with real Keycloak tokens:
 * an ADMIN sets a user's own limit, the user is refused (507 {@code UserQuotaExceeded}) beyond it,
 * sees it on {@code /quotas/me} and on the dashboard, and gets the default back when the override
 * is removed. Non-admins cannot touch the administration endpoints.
 * <p>
 * The default per-user quota is 50 MB here, so the override is what bites: limits are set relative
 * to the user's current usage, which keeps the test independent of what other suites uploaded.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class StorageQuotaAdminIT extends TestContainersKeyCloakConfig {

    private static final int ONE_MB = 1024 * 1024;
    private static final String USER = "contributor-user@test.com";
    private static final String ADMIN_QUOTAS = RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_ADMIN_QUOTAS;

    private String adminToken;
    private String contributorToken;
    private String readerToken;
    private final List<UUID> uploaded = new ArrayList<>();

    public StorageQuotaAdminIT(WebTestClient webTestClient, JacksonJsonEncoder customJacksonJsonEncoder) {
        super(webTestClient, customJacksonJsonEncoder);
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> keycloak.getAuthServerUrl() + "/realms/openfilz/protocol/openid-connect/certs");
        registry.add("openfilz.security.no-auth", () -> false);
        registry.add("openfilz.quota.user", () -> 50);
        registry.add("openfilz.quota.file-upload", () -> 20);
    }

    @BeforeEach
    void tokens() {
        adminToken = getAccessToken("admin-user");
        contributorToken = getAccessToken("contributor-user");
        readerToken = getAccessToken("reader-user");
    }

    @AfterEach
    void cleanUp() {
        client(adminToken).delete().uri(ADMIN_QUOTAS + "/users/{u}", USER).exchange();
        if (!uploaded.isEmpty()) {
            client(adminToken).method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(new DeleteRequest(List.copyOf(uploaded)))
                    .exchange();
            uploaded.clear();
        }
    }

    @Test
    void userOverride_isEnforced_shownToTheUser_andRemovable() {
        // Current usage, as the admin sees it
        QuotaUsage before = client(adminToken).get().uri(ADMIN_QUOTAS + "/users/{u}", USER).exchange()
                .expectStatus().isOk().expectBody(QuotaUsage.class).returnResult().getResponseBody();
        assertNotNull(before);
        assertEquals(QuotaSource.DEFAULT, before.source());
        assertEquals(50L * ONE_MB, before.limitBytes());

        // Own limit: current usage (rounded up to the MB) + 1 MB
        long limitMb = before.usedBytes() / ONE_MB + 2;
        QuotaUsage set = client(adminToken).put().uri(ADMIN_QUOTAS + "/users/{u}", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserQuotaRequest(limitMb))
                .exchange()
                .expectStatus().isOk().expectBody(QuotaUsage.class).returnResult().getResponseBody();
        assertNotNull(set);
        assertEquals(QuotaSource.USER, set.source());
        assertEquals(limitMb, set.overrideMb());
        assertEquals(limitMb * ONE_MB, set.limitBytes());

        // The user sees it
        MyStorageQuota mine = client(contributorToken).get().uri(RestApiVersion.API_PREFIX + "/quotas/me").exchange()
                .expectStatus().isOk().expectBody(MyStorageQuota.class).returnResult().getResponseBody();
        assertNotNull(mine);
        assertEquals(QuotaSource.USER, mine.source());
        assertEquals(limitMb * ONE_MB, mine.limitBytes());
        assertEquals(20L * ONE_MB, mine.maxFileSizeBytes());

        // ... and on the dashboard ring
        DashboardStatisticsResponse stats = client(contributorToken).get().uri(RestApiVersion.API_PREFIX + "/dashboard/statistics")
                .exchange().expectStatus().isOk().expectBody(DashboardStatisticsResponse.class).returnResult().getResponseBody();
        assertNotNull(stats);
        assertNotNull(stats.storage().quota());
        assertEquals(limitMb * ONE_MB, stats.storage().quota().limitBytes());

        // Over the override: 507 with a machine-readable code, although the default (50 MB) would allow it
        ErrorResponse refused = upload(contributorToken, "over-override.bin", 3 * ONE_MB)
                .expectStatus().isEqualTo(HttpStatus.INSUFFICIENT_STORAGE)
                .expectBody(ErrorResponse.class).returnResult().getResponseBody();
        assertNotNull(refused);
        assertEquals(OpenFilzException.USER_QUOTA_EXCEEDED, refused.error());
        assertTrue(refused.message().contains(USER));

        // Within it: accepted
        UploadResponse ok = upload(contributorToken, "within-override.bin", ONE_MB / 2)
                .expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertNotNull(ok);
        uploaded.add(ok.id());

        // Removing the override gives the default back, and the upload refused above now passes
        QuotaUsage cleared = client(adminToken).delete().uri(ADMIN_QUOTAS + "/users/{u}", USER).exchange()
                .expectStatus().isOk().expectBody(QuotaUsage.class).returnResult().getResponseBody();
        assertNotNull(cleared);
        assertEquals(QuotaSource.DEFAULT, cleared.source());
        assertNull(cleared.overrideMb());
        UploadResponse nowOk = upload(contributorToken, "after-clear.bin", 3 * ONE_MB)
                .expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertNotNull(nowOk);
        uploaded.add(nowOk.id());
    }

    @Test
    void overrideZero_exemptsTheUserFromTheDefault() {
        client(adminToken).put().uri(ADMIN_QUOTAS + "/users/{u}", USER)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new UserQuotaRequest(0L))
                .exchange().expectStatus().isOk();

        MyStorageQuota mine = client(contributorToken).get().uri(RestApiVersion.API_PREFIX + "/quotas/me").exchange()
                .expectStatus().isOk().expectBody(MyStorageQuota.class).returnResult().getResponseBody();
        assertNotNull(mine);
        assertNull(mine.limitBytes(), "0 = no limit for this user");
        assertEquals(QuotaSource.USER, mine.source());
    }

    @Test
    void listing_showsUsersSortedByUsage_withOverview() {
        UploadResponse file = upload(contributorToken, "listing.bin", ONE_MB)
                .expectStatus().isCreated().expectBody(UploadResponse.class).returnResult().getResponseBody();
        assertNotNull(file);
        uploaded.add(file.id());

        QuotaUserPage page = client(adminToken).get()
                .uri(uri -> uri.path(ADMIN_QUOTAS + "/users").queryParam("sort", "usage").queryParam("order", "desc")
                        .queryParam("size", 500).build())
                .exchange().expectStatus().isOk().expectBody(QuotaUserPage.class).returnResult().getResponseBody();
        assertNotNull(page);
        assertTrue(page.items().stream().anyMatch(u -> u.username().equals(USER)));
        for (int i = 1; i < page.items().size(); i++) {
            assertTrue(page.items().get(i - 1).usedBytes() >= page.items().get(i).usedBytes(), "sorted by usage, descending");
        }

        QuotaOverview overview = client(adminToken).get().uri(ADMIN_QUOTAS).exchange()
                .expectStatus().isOk().expectBody(QuotaOverview.class).returnResult().getResponseBody();
        assertNotNull(overview);
        assertEquals(50, overview.defaultUserMb());
        assertEquals(20, overview.fileUploadMb());
        assertTrue(overview.instanceUsedBytes() >= ONE_MB);
        assertNull(overview.instanceLimitBytes());
    }

    @Test
    void administration_isReservedToAdmins() {
        client(contributorToken).get().uri(ADMIN_QUOTAS + "/users").exchange().expectStatus().isForbidden();
        client(readerToken).put().uri(ADMIN_QUOTAS + "/users/{u}", USER)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new UserQuotaRequest(1L))
                .exchange().expectStatus().isForbidden();
        client(contributorToken).delete().uri(ADMIN_QUOTAS + "/users/{u}", USER).exchange().expectStatus().isForbidden();
        // ... while every user reads their own quota
        client(readerToken).get().uri(RestApiVersion.API_PREFIX + "/quotas/me").exchange().expectStatus().isOk();
    }

    @Test
    void invalidOverride_isRefused() {
        client(adminToken).put().uri(ADMIN_QUOTAS + "/users/{u}", USER)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new UserQuotaRequest(-5L))
                .exchange().expectStatus().isBadRequest();
        client(adminToken).put().uri(ADMIN_QUOTAS + "/users/{u}", USER)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(new UserQuotaRequest(null))
                .exchange().expectStatus().isBadRequest();
    }

    // ------------------------------------------------------------------ helpers

    private WebTestClient client(String token) {
        return webTestClient.mutate().defaultHeader("Authorization", "Bearer " + token).build();
    }

    private WebTestClient.ResponseSpec upload(String token, String filename, int size) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ByteArrayResource(new byte[size]) {
            @Override
            public String getFilename() {
                return filename;
            }
        });
        return client(token).post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload").queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }
}
