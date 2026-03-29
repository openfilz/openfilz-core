package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
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
 * E2E tests for the recursive search scope feature.
 *
 * Test hierarchy:
 *   ScopeTest/
 *   ├── root-file.txt          (no metadata)
 *   ├── root-contract.txt      (metadata: {type: Contract})
 *   └── SubFolder/
 *       ├── sub-file.txt       (no metadata)
 *       ├── sub-contract.txt   (metadata: {type: Contract})
 *       └── DeepFolder/
 *           └── deep-contract.txt (metadata: {type: Contract})
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class SearchScopeIT extends TestContainersBaseConfig {

    private HttpGraphQlClient graphQlClient;

    // Folder IDs
    private UUID scopeTestId;
    private UUID subFolderId;
    private UUID deepFolderId;

    // File IDs (for cleanup)
    private UUID rootFileId;
    private UUID rootContractId;
    private UUID subFileId;
    private UUID subContractId;
    private UUID deepContractId;

    public SearchScopeIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    @BeforeEach
    void setUp() {
        graphQlClient = newGraphQlClient();

        // Create folder hierarchy
        scopeTestId = createFolder("ScopeTest-" + UUID.randomUUID().toString().substring(0, 8), null).id();
        subFolderId = createFolder("SubFolder", scopeTestId).id();
        deepFolderId = createFolder("DeepFolder", subFolderId).id();

        // Upload files
        rootFileId = uploadFile("test1.txt", scopeTestId, null).id();
        rootContractId = uploadFile("test2.txt", scopeTestId, Map.of("type", "Contract")).id();
        subFileId = uploadFile("test1.txt", subFolderId, null).id();
        subContractId = uploadFile("test2.txt", subFolderId, Map.of("type", "Contract")).id();
        deepContractId = uploadFile("test1.txt", deepFolderId, Map.of("type", "Contract")).id();
    }

    @Test
    void whenListFolderNonRecursive_thenOnlyDirectChildrenReturned() {
        var query = listFolderQuery(scopeTestId, Map.of("type", "Contract"), false);

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    var items = getListFolderItems(doc);
                    return items.size() == 1
                            && items.getFirst().get("name").equals("test2.txt");
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderRecursiveOnRoot_thenAllDescendantsReturned() {
        var query = listFolderQuery(scopeTestId, Map.of("type", "Contract"), true);

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    var items = getListFolderItems(doc);
                    return items.size() == 3;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderRecursiveOnSubFolder_thenOnlySubFolderDescendantsReturned() {
        var query = listFolderQuery(subFolderId, Map.of("type", "Contract"), true);

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    var items = getListFolderItems(doc);
                    return items.size() == 2;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListAllFolder_thenDocumentsFromAllFoldersReturned() {
        var query = """
                query {
                  listAllFolder(request: {
                    metadata: { type: "Contract" },
                    pageInfo: { pageNumber: 1, pageSize: 100, sortBy: "name", sortOrder: ASC }
                  }) {
                    id
                    name
                    type
                  }
                }""";

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    var items = getListAllFolderItems(doc);
                    // At least 3 from our setup (may be more from pre-existing data)
                    return items.size() >= 3;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenListFolderRecursiveNoMetadataFilter_thenAllDescendantsReturned() {
        var query = String.format("""
                query {
                  listFolder(request: {
                    id: "%s",
                    recursive: true,
                    pageInfo: { pageNumber: 1, pageSize: 100, sortBy: "name", sortOrder: ASC }
                  }) {
                    id
                    name
                    type
                  }
                }""", scopeTestId);

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    var items = getListFolderItems(doc);
                    // 5 files + 2 subfolders = 7 total descendants
                    return items.size() == 7;
                })
                .expectComplete()
                .verify();
    }

    @Test
    void whenCountRecursive_thenCountMatchesListResults() {
        var query = String.format("""
                query {
                  count(request: {
                    id: "%s",
                    metadata: { type: "Contract" },
                    recursive: true
                  })
                }""", scopeTestId);

        StepVerifier.create(graphQlClient.document(query).execute())
                .expectNextMatches(doc -> {
                    Integer count = ((Map<String, Integer>) doc.getData()).get("count");
                    return count == 3;
                })
                .expectComplete()
                .verify();
    }

    // ==================== Helpers ====================

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

    private UploadResponse uploadFile(String filename, UUID parentFolderId, Map<String, Object> metadata) {
        MultipartBodyBuilder builder = newFileBuilder(filename);
        builder.part("parentFolderId", parentFolderId.toString());
        if (metadata != null) {
            builder.part("metadata", metadata);
        }
        return getUploadResponse(builder, true);
    }

    private String listFolderQuery(UUID folderId, Map<String, Object> metadata, boolean recursive) {
        StringBuilder metadataJson = new StringBuilder("{");
        metadata.forEach((k, v) -> metadataJson.append(" ").append(k).append(": \"").append(v).append("\""));
        metadataJson.append(" }");

        return String.format("""
                query {
                  listFolder(request: {
                    id: "%s",
                    metadata: %s,
                    recursive: %s,
                    pageInfo: { pageNumber: 1, pageSize: 100, sortBy: "name", sortOrder: ASC }
                  }) {
                    id
                    name
                    type
                  }
                }""", folderId, metadataJson, recursive);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListFolderItems(ClientGraphQlResponse doc) {
        return (List<Map<String, Object>>) ((Map<String, Object>) doc.getData()).get("listFolder");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getListAllFolderItems(ClientGraphQlResponse doc) {
        return (List<Map<String, Object>>) ((Map<String, Object>) doc.getData()).get("listAllFolder");
    }
}
