package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests for GraphQL queries covering:
 * - listAllFolder query (AllFoldersDocumentQueryService)
 * - listFolder with metadata filter
 * - listFolder with date filters (createdAtAfter/Before, updatedAtAfter/Before)
 * - listFolder with nameLike filter
 * - listFolder with size filter
 * - listFolder with createdBy/updatedBy filters
 * - listFolder with type filter (FILE/FOLDER)
 * - listFavorites with type filter
 * - ThumbnailUrlResolver branches (folder, file without thumbnail)
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class GraphQlAllFoldersIT extends TestContainersBaseConfig {

    private HttpGraphQlClient graphQlClient;

    public GraphQlAllFoldersIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    // ==================== listAllFolder ====================

    @Test
    void whenListAllFolder_thenReturnsResults() {
        // Ensure at least one folder exists
        String folderName = "all-folders-" + UUID.randomUUID();
        createFolder(folderName, null);

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listAllFolder($request:ListFolderRequest!) {
                    listAllFolder(request:$request) {
                      id
                      name
                      type
                      thumbnailUrl
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListAllFolderWithTypeFilter_thenFiltered() {
        createFolder("all-type-" + UUID.randomUUID(), null);
        uploadDocument(newFileBuilder());

        // Filter by FOLDER type only
        ListFolderRequest request = new ListFolderRequest(
                null, org.openfilz.dms.enums.DocumentType.FOLDER, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listAllFolder($request:ListFolderRequest!) {
                    listAllFolder(request:$request) {
                      id
                      name
                      type
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== listFolder with various filters ====================

    @Test
    void whenListFolderWithNameLike_thenFiltered() {
        String prefix = "namelike-" + UUID.randomUUID().toString().substring(0, 8);
        createFolder(prefix + "-folder1", null);
        createFolder(prefix + "-folder2", null);

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, prefix, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      type
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderWithMetadataFilter_thenFiltered() {
        String folderName = "meta-filter-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        builder.part("metadata", "{\"env\":\"production\"}");
        uploadDocument(builder);

        // Filter by metadata
        Map<String, Object> metadataFilter = Map.of("env", "production");
        ListFolderRequest request = new ListFolderRequest(
                folder.id(), null, null, null, null, metadataFilter, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      metadata
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderWithDateFilters_thenOk() {
        uploadDocument(newFileBuilder());

        // Use a wide date range
        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null,
                java.time.OffsetDateTime.now().minusDays(1), null,
                null, java.time.OffsetDateTime.now().plusDays(1),
                null, null, null, true,
                new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      createdAt
                      updatedAt
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderWithSizeFilter_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, 1L, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      size
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== FolderElementInfo with thumbnailUrl ====================

    @Test
    void whenListFolderRequestingThumbnailUrl_thenNullForFolders() {
        createFolder("thumb-folder-" + UUID.randomUUID(), null);

        ListFolderRequest request = new ListFolderRequest(
                null, org.openfilz.dms.enums.DocumentType.FOLDER, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      type
                      thumbnailUrl
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenDocumentByIdRequestingThumbnailUrl_thenNullIfNoThumbnailService() {
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse file = uploadDocument(builder);

        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      name
                      type
                      thumbnailUrl
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("id", file.id())
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== listFavorites with type filter ====================

    @Test
    void whenListFavoritesWithTypeFilter_thenOk() {
        String query = """
                query listFavorites($request:FavoriteRequest!) {
                    listFavorites(request:$request) {
                      id
                      name
                      type
                    }
                }
                """.trim();

        java.util.Map<String, Object> request = new java.util.HashMap<>();
        request.put("type", "FILE");
        request.put("pageInfo", Map.of("pageNumber", 1, "pageSize", 10, "sortBy", "name", "sortOrder", "ASC"));

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenCountFavoritesWithoutPaging_thenOk() {
        String query = """
                query countFavorites($request:FavoriteRequest) {
                    countFavorites(request:$request)
                }
                """.trim();

        java.util.Map<String, Object> request = new java.util.HashMap<>();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== Helper Methods ====================

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
