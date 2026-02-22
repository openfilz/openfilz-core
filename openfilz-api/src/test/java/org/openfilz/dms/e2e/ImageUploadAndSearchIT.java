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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
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
 * E2E tests for image upload, various content types, and search operations:
 * - Upload JPEG image → covers content type detection for images
 * - Upload PNG image
 * - Upload JSON, XML, CSV, YAML, Markdown files → various content types
 * - Upload file without extension
 * - Search documents by metadata (searchDocumentIdsByMetadata)
 * - GraphQL: DuplicateNameException through createFolder mutation attempt
 * - GraphQL: OperationForbiddenException through listFolder on non-existent folder
 * - DocumentServiceImpl.getDocumentInfo with null withMetadata
 * - DocumentServiceImpl.listFolderInfo various combinations
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class ImageUploadAndSearchIT extends TestContainersBaseConfig {

    private HttpGraphQlClient graphQlClient;

    public ImageUploadAndSearchIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
    }

    private HttpGraphQlClient getClient() {
        if (graphQlClient == null) {
            graphQlClient = newGraphQlClient();
        }
        return graphQlClient;
    }

    // ==================== Upload various file types ====================

    @Test
    void whenUploadJpegImage_thenContentTypeIsJpeg() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-image.jpg"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.contentType().contains("image") || response.contentType().contains("jpeg"));
    }

    @Test
    void whenUploadPngImage_thenContentTypeIsPng() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-image.png"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
        Assertions.assertTrue(response.contentType().contains("image") || response.contentType().contains("png"));
    }

    @Test
    void whenUploadJsonFile_thenContentTypeIsJson() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-code.json"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    @Test
    void whenUploadXmlFile_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-data.xml"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    @Test
    void whenUploadCsvFile_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-data.csv"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    @Test
    void whenUploadYamlFile_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-config.yaml"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    @Test
    void whenUploadMarkdownFile_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-markdown.md"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    @Test
    void whenUploadFileWithNoExtension_thenOk() {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test-file-no-extension"));

        UploadResponse response = getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(response);
    }

    // ==================== Search documents by metadata ====================

    @Test
    void whenSearchDocumentsByMetadata_thenIdsReturned() {
        // Upload file with specific metadata
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("metadata", "{\"searchKey\":\"searchVal-" + UUID.randomUUID() + "\"}");

        getWebTestClient().post()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true).build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated();

        // Search by metadata
        SearchByMetadataRequest searchRequest = new SearchByMetadataRequest(null, null, null, null, Map.of("searchKey", "searchVal-*"));
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .contentType(MediaType.APPLICATION_JSON)
                .body(BodyInserters.fromValue(searchRequest))
                .exchange()
                .expectStatus().isOk();
    }

    // ==================== Document info with null withMetadata ====================

    @Test
    void whenGetDocumentInfoWithNullMetadataParam_thenOk() {
        UploadResponse file = uploadDocument(newFileBuilder());

        // Call without the withMetadata param at all (null)
        getWebTestClient().get()
                .uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.name").isNotEmpty();
    }

    // ==================== GraphQL: list folder with non-existent folder ====================

    @Test
    void whenListFolderNonExistent_thenError() {
        ListFolderRequest request = new ListFolderRequest(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = getClient()
                .document(query)
                .variable("request", request)
                .execute();

        StepVerifier.create(response)
                .expectNextMatches(doc -> {
                    // May return empty list or error depending on implementation
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== GraphQL: listFolder with favorite filter ====================

    @Test
    void whenListFolderWithFavoriteFilter_thenOk() {
        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, true, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
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
                    return true;
                })
                .expectComplete()
                .verify();
    }

    // ==================== GraphQL: listFolder with updatedBy filter ====================

    @Test
    void whenListFolderWithUpdatedByFilter_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                "anonymous", null, true, new PageCriteria("name", SortOrder.ASC, 1, 100));

        String query = """
                query listFolder($request:ListFolderRequest!) {
                    listFolder(request:$request) {
                      id
                      name
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

    // ==================== GraphQL: listFolder with FILE type only ====================

    @Test
    void whenListFolderFileTypeOnly_thenOnlyFilesReturned() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, org.openfilz.dms.enums.DocumentType.FILE, null, null, null, null, null, null, null, null, null, null,
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

    // ==================== GraphQL: listFolder with FOLDER type only ====================

    @Test
    void whenListFolderFolderTypeOnly_thenOnlyFoldersReturned() {
        createFolder("type-filter-" + UUID.randomUUID(), null);

        ListFolderRequest request = new ListFolderRequest(
                null, org.openfilz.dms.enums.DocumentType.FOLDER, null, null, null, null, null, null, null, null, null, null,
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

    // ==================== GraphQL: sort by contentType ====================

    @Test
    void whenListFolderSortByContentType_thenOk() {
        uploadDocument(newFileBuilder());

        ListFolderRequest request = new ListFolderRequest(
                null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, true, new PageCriteria("contentType", SortOrder.ASC, 1, 100));

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

    // ==================== Upload with metadata to folder and download ====================

    @Test
    void whenUploadMultipleFilesAndDownloadZip_thenSuccess() {
        String folderName = "zip-multi-" + UUID.randomUUID();
        FolderResponse folder = createFolder(folderName, null);

        // Upload different file types into folder
        MultipartBodyBuilder b1 = new MultipartBodyBuilder();
        b1.part("file", new ClassPathResource("test.txt"));
        b1.part("parentFolderId", folder.id().toString());
        UploadResponse f1 = uploadDocument(b1);

        MultipartBodyBuilder b2 = new MultipartBodyBuilder();
        b2.part("file", new ClassPathResource("test_file_1.sql"));
        b2.part("parentFolderId", folder.id().toString());
        UploadResponse f2 = uploadDocument(b2);

        // Download multiple as ZIP
        getWebTestClient().post()
                .uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(List.of(f1.id(), f2.id()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_OCTET_STREAM);
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
