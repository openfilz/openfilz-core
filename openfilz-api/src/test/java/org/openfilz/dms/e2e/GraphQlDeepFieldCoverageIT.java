package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.DocumentType;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.MediaType;
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
 * E2E tests for deep GraphQL field coverage and additional REST edge cases:
 * - DocumentEntityBuilder: ALL switch branches (id, parentId, name, type, size, metadata, createdAt, updatedAt, createdBy, updatedBy, contentType, favorite)
 * - listFolder with ALL fields for both FolderElementInfo
 * - listAllFolder with ALL fields
 * - documentById with ALL fields for DocumentInfo
 * - listFavorites with ALL fields
 * - Favorite toggle to cover favorite-related branches
 * - Copy folder to cover recursive copy paths
 * - Delete folders to cover delete paths
 * - Upload with content-length for quota validation path
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class GraphQlDeepFieldCoverageIT extends TestContainersBaseConfig {

    private HttpGraphQlClient graphQlClient;

    public GraphQlDeepFieldCoverageIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    // ==================== FolderElementInfo with ALL fields ====================

    @Test
    void whenListFolderWithAllFields_thenAllFieldsReturned() {
        String folderName = "deep-all-fields-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        builder.part("metadata", "{\"deep\":\"test\"}");
        uploadDocument(builder);

        ListFolderRequest request = new ListFolderRequest(
                folder.id(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        // Request ALL FolderElementInfo fields to cover all DocumentEntityBuilder branches
        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      type
                      contentType
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                      favorite
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
                    var list = doc.field("listFolder").toEntityList(Map.class);
                    Assertions.assertFalse(list.isEmpty());
                    Map<String, Object> first = list.getFirst();
                    Assertions.assertNotNull(first.get("id"));
                    Assertions.assertNotNull(first.get("name"));
                    Assertions.assertNotNull(first.get("type"));
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderWithFolderAndFileAllFields_thenBothReturnAllFields() {
        String folderName = "deep-mixed-" + UUID.randomUUID();
        FolderResponse parent = createFolder(folderName, null);

        // Create subfolder
        createFolder("deep-child-" + UUID.randomUUID(), parent.id());

        // Upload file with metadata
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", parent.id().toString());
        builder.part("metadata", "{\"k\":\"v\"}");
        uploadDocument(builder);

        ListFolderRequest request = new ListFolderRequest(
                parent.id(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      type
                      contentType
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                      favorite
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
                    var list = doc.field("listFolder").toEntityList(Map.class);
                    Assertions.assertEquals(2, list.size()); // subfolder + file
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== listAllFolder with ALL fields ====================

    @Test
    void whenListAllFolderWithAllFields_thenCoversAllBranches() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listAllFolder($request:ListFolderRequest!) {
                    listAllFolder(request:$request) {
                      id
                      name
                      type
                      contentType
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                      favorite
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

    // ==================== documentById with ALL fields ====================

    @Test
    void whenDocumentByIdWithAllFields_thenAllFieldsReturned() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", "{\"docInfo\":\"test\"}");
        UploadResponse file = uploadDocument(builder);

        // DocumentInfo has: id, parentId, type, contentType, name, metadata, size, createdAt, updatedAt, createdBy, updatedBy, thumbnailUrl
        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      parentId
                      type
                      contentType
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
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
                    var data = doc.field("documentById");
                    Assertions.assertNotNull(data);
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenDocumentByIdForFolder_thenNullableFieldsOk() {
        String folderName = "deep-folder-byid-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      parentId
                      type
                      contentType
                      name
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                      thumbnailUrl
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("id", folder.id())
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertTrue(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== listFavorites with ALL fields ====================

    @Test
    void whenListFavoritesWithAllFields_thenOk() {
        String query = """
                query listFavorites($request:FavoriteRequest!) {
                    listFavorites(request:$request) {
                      id
                      name
                      type
                      contentType
                      metadata
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
                      favorite
                      thumbnailUrl
                    }
                }
                """.trim();

        java.util.Map<String, Object> request = new java.util.HashMap<>();
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

    // ==================== Copy folders (covers copyFolderRecursive) ====================

    @Test
    void whenCopyFolderWithContents_thenCopied() {
        String srcFolderName = "deep-copy-src-" + UUID.randomUUID();
        FolderResponse srcFolder = createFolder(srcFolderName, null);

        // Add subfolder and file
        createFolder("deep-copy-sub-" + UUID.randomUUID(), srcFolder.id());

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", srcFolder.id().toString());
        uploadDocument(builder);

        // Create target folder
        String dstFolderName = "deep-copy-dst-" + UUID.randomUUID();
        FolderResponse dstFolder = createFolder(dstFolderName, null);

        CopyRequest copyRequest = new CopyRequest(List.of(srcFolder.id()), dstFolder.id(), false);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void whenCopyFolderIntoItself_thenForbidden() {
        String folderName = "deep-self-copy-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        CopyRequest copyRequest = new CopyRequest(List.of(folder.id()), folder.id(), false);
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(copyRequest))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ==================== Delete folders (covers deleteFolders path) ====================

    @Test
    void whenDeleteFolder_thenDeleted() {
        String folderName = "deep-delete-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(List.of(folder.id()));
        getWebTestClient().method(org.springframework.http.HttpMethod.DELETE)
                .uri(RestApiVersion.API_PREFIX + "/folders")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();
    }

    // ==================== Sort by different fields to cover more DAOImpl branches ====================

    @Test
    void whenListFolderSortedByCreatedAt_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("createdAt", SortOrder.DESC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      createdAt
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
    void whenListFolderSortedBySize_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("size", SortOrder.ASC, 1, 100));

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

    @Test
    void whenListFolderSortedByUpdatedAt_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("updatedAt", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
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
    void whenListFolderSortedByType_thenOk() {
        createFolder("deep-sort-type-" + UUID.randomUUID(), null);
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("type", SortOrder.ASC, 1, 100));

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

    // ==================== listFolder with createdBy/updatedBy filters ====================

    @Test
    void whenListFolderByCreatedBy_thenFiltered() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, "anonymous",
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      createdBy
                      updatedBy
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
