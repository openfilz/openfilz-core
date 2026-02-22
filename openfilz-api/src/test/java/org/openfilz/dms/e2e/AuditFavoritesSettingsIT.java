package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.SearchByAuditLogRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.AuditAction;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for audit, favorites, settings, dashboard, and suggestions:
 * - Audit trail: GET by resource ID with sort ASC/DESC
 * - Audit search: POST with various criteria
 * - Audit verify: GET chain integrity
 * - Favorites: add, toggle, check, remove
 * - Settings: get settings
 * - Dashboard: get statistics
 * - Suggestions: search suggestions
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class AuditFavoritesSettingsIT extends TestContainersBaseConfig {

    public AuditFavoritesSettingsIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    // ==================== Audit Trail ====================

    @Test
    void whenGetAuditTrailAsc_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/audit/{id}")
                        .queryParam("sort", "ASC")
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenGetAuditTrailDesc_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/audit/{id}")
                        .queryParam("sort", "DESC")
                        .build(file.id()))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenGetAuditTrailNoSort_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/{id}", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Audit Search ====================

    @Test
    void whenSearchAuditByAction_thenOk() {
        uploadDocument(newFileBuilder());

        SearchByAuditLogRequest request = new SearchByAuditLogRequest(null, null, null, AuditAction.UPLOAD_DOCUMENT, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchAuditByResourceType_thenOk() {
        uploadDocument(newFileBuilder());

        SearchByAuditLogRequest request = new SearchByAuditLogRequest(null, null, DocumentType.FILE, null, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchAuditByUsername_thenOk() {
        uploadDocument(newFileBuilder());

        SearchByAuditLogRequest request = new SearchByAuditLogRequest("anonymous", null, null, null, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchAuditByDocumentId_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        SearchByAuditLogRequest request = new SearchByAuditLogRequest(null, file.id(), null, null, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenSearchAuditCombinedCriteria_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        SearchByAuditLogRequest request = new SearchByAuditLogRequest(
                "anonymous", file.id(), DocumentType.FILE, AuditAction.UPLOAD_DOCUMENT, null);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/audit/search")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(request)
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Audit Verify Chain ====================

    @Test
    void whenVerifyAuditChain_thenOk() {
        // Ensure there are some audit entries
        uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/audit/verify")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Favorites ====================

    @Test
    void whenAddFavorite_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenCheckIsFavorite_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Add to favorites first
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", file.id())
                .exchange()
                .expectStatus().isOk();

        // Check if favorite
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}/is-favorite", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenToggleFavorite_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Toggle (add)
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}/toggle", file.id())
                .exchange()
                .expectStatus().isOk();

        // Toggle again (remove)
        getWebTestClient().put()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}/toggle", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenRemoveFavorite_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Add first
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", file.id())
                .exchange()
                .expectStatus().isOk();

        // Remove
        getWebTestClient().delete()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", file.id())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenFavoriteFolder_thenOk() {
        FolderResponse folder = createFolder("fav-folder-" + UUID.randomUUID(), null);

        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/favorites/{id}", folder.id())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Settings ====================

    @Test
    void whenGetSettings_thenOk() {
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/settings")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Dashboard ====================

    @Test
    void whenGetDashboardStatistics_thenOk() {
        // Ensure some data exists
        uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/dashboard/statistics")
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Suggestions ====================

    @Test
    void whenGetSuggestions_thenOk() {
        uploadDocument(newFileBuilder());

        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", "test")
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenGetSuggestionsEmptyQuery_thenOk() {
        getWebTestClient().get()
                .uri(uriBuilder -> uriBuilder
                        .path(RestApiVersion.API_PREFIX + "/suggestions")
                        .queryParam("q", "nonexistent_abcxyz")
                        .build())
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Helper ====================

    private FolderResponse createFolder(String name, UUID parentId) {
        CreateFolderRequest request = new CreateFolderRequest(name, parentId);
        return getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(request))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();
    }
}
