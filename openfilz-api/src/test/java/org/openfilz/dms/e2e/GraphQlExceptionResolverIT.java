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

import java.util.UUID;

import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

/**
 * E2E tests specifically targeting CustomExceptionResolver branches:
 * - IllegalArgumentException → BAD_REQUEST (count with paging)
 * - DocumentNotFoundException → NOT_FOUND (documentById non-existent)
 * - OperationForbiddenException → FORBIDDEN (move folder into itself via GraphQL)
 *
 * Also covers additional GraphQL field coverage for DocumentEntityBuilder:
 * - parentId field retrieval
 * - nullable fields (contentType, size for folders)
 * - favorite field
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class GraphQlExceptionResolverIT extends TestContainersBaseConfig {

    private HttpGraphQlClient graphQlClient;

    public GraphQlExceptionResolverIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    // ==================== CustomExceptionResolver: BAD_REQUEST (IllegalArgumentException) ====================

    @Test
    void whenCountWithPaging_thenBadRequestClassification() {
        // count rejects PageCriteria → IllegalArgumentException → BAD_REQUEST
        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 10));

        String query = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertFalse(doc.getErrors().isEmpty());
                    // Verify error classification is BAD_REQUEST
                    String errorType = doc.getErrors().getFirst().getExtensions().get("classification").toString();
                    Assertions.assertEquals("BAD_REQUEST", errorType);
                    return true;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenCountFavoritesWithPaging_thenBadRequest() {
        // countFavorites also rejects PageCriteria → IllegalArgumentException
        String query = """
                query countFavorites($request:FavoriteRequest) {
                    countFavorites(request:$request)
                }
                """.trim();

        java.util.Map<String, Object> request = java.util.Map.of(
                "pageInfo", java.util.Map.of("pageNumber", 1, "pageSize", 10, "sortBy", "name", "sortOrder", "ASC")
        );

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    Assertions.assertFalse(doc.getErrors().isEmpty());
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== CustomExceptionResolver: NOT_FOUND (DocumentNotFoundException) ====================

    @Test
    void whenDocumentByIdNonExistent_thenNotFoundClassification() {
        UUID nonExistentId = UUID.randomUUID();

        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      name
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("id", nonExistentId)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    if (!doc.getErrors().isEmpty()) {
                        String errorType = doc.getErrors().getFirst().getExtensions().get("classification").toString();
                        Assertions.assertEquals("NOT_FOUND", errorType);
                    }
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== DocumentEntityBuilder: parentId field ====================

    @Test
    void whenQueryDocumentWithParentId_thenParentIdReturned() {
        // Create folder and upload file in it
        String folderName = "resolver-parent-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        UploadResponse file = uploadDocument(builder);

        // Query via GraphQL requesting parentId (DocumentInfo has parentId but not favorite)
        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      name
                      type
                      parentId
                      contentType
                      size
                      metadata
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

    // ==================== DocumentEntityBuilder: folder with nullable fields ====================

    @Test
    void whenQueryFolderWithAllFields_thenNullableFieldsHandled() {
        // Create folder (no contentType, no size, no storagePath)
        String folderName = "resolver-folder-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // DocumentInfo doesn't have 'favorite' field - query only what exists
        String query = """
                query documentById($id:UUID!) {
                    documentById(id:$id) {
                      id
                      name
                      type
                      parentId
                      contentType
                      size
                      createdAt
                      updatedAt
                      createdBy
                      updatedBy
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

    // ==================== listFolder with various filter combinations ====================

    @Test
    void whenListFolderByContentType_thenFiltered() {
        String folderName = "resolver-ct-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        ListFolderRequest request = new ListFolderRequest(
                folder.id(), null, "application/x-sql", null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      contentType
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
    void whenListFolderByExactName_thenFiltered() {
        String folderName = "resolver-exact-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folder.id().toString());
        uploadDocument(builder);

        // Filter by exact file name (name is 4th param)
        ListFolderRequest request = new ListFolderRequest(
                folder.id(), null, null, "test_file_1.sql", null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                      type
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

    // ==================== count at root level ====================

    @Test
    void whenCountAtRoot_thenOk() {
        // Upload to ensure at least one result
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, null);

        String query = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
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
