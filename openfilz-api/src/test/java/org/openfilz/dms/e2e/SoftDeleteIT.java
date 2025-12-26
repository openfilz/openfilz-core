package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.request.CreateFolderRequest;
import org.openfilz.dms.dto.request.DeleteRequest;
import org.openfilz.dms.dto.request.ListFolderRequest;
import org.openfilz.dms.dto.response.DocumentInfo;
import org.openfilz.dms.dto.response.FolderElementInfo;
import org.openfilz.dms.dto.response.FolderResponse;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.http.codec.json.Jackson2JsonEncoder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import org.openfilz.dms.repository.DocumentRepository;
import org.openfilz.dms.entity.Document;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.openfilz.dms.enums.AuditAction.CREATE_FOLDER;
import static org.openfilz.dms.enums.AuditAction.DELETE_FOLDER;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class SoftDeleteIT extends TestContainersBaseConfig {

    protected HttpGraphQlClient graphQlHttpClient;

    private final DocumentRepository documentRepository;

    @Value("${storage.local.base-path:/tmp/dms-storage}")
    private String storageBasePath;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.soft-delete.active", () -> true);
    }

    public SoftDeleteIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder,
                        DocumentRepository documentRepository) {
        super(webTestClient, customJackson2JsonEncoder);
        this.documentRepository = documentRepository;
    }

    private HttpGraphQlClient getGraphQlHttpClient() {
        if(graphQlHttpClient == null) {
            graphQlHttpClient = newGraphQlClient();
        }
        return graphQlHttpClient;
    }


    @Test
    void whenDeleteDocument_thenNoContent() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isNotFound();
    }



    @Test
    void whenDeleteFiles_thenOk() {
        MultipartBodyBuilder builder = newFileBuilder();

        UploadResponse response = uploadDocument(builder);

        UploadResponse response2 = uploadDocument(newFileBuilder());

        DeleteRequest deleteRequest = new DeleteRequest(List.of(response.id(), response2.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response2.id())
                .exchange()
                .expectStatus().isNotFound();
    }



    @Test
    void testRestore() {
        RestoreHandler folderAndFile1 = createFolderAndFile("test-restore-folder-1", null);
        RestoreHandler folderAndFile2 = createFolderAndFile("test-restore-folder-2", null);
        RestoreHandler folderAndFile1_1 = createFolderAndFile("test-restore-folder-1-1", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_1 = createFolderAndFile("test-restore-folder-2-1", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_2 = createFolderAndFile("test-restore-folder-1-2", folderAndFile1.parent().id());
        RestoreHandler folderAndFile2_2 = createFolderAndFile("test-restore-folder-2-2", folderAndFile2.parent().id());
        RestoreHandler folderAndFile1_1_1 = createFolderAndFile("test-restore-folder-1-1-1", folderAndFile1_1.parent().id());
        MultipartBodyBuilder builder = newFileBuilder();
        UploadResponse rootFile = uploadDocument(builder);

        builder = newFileBuilder();
        builder.part("parentFolderId", folderAndFile1_1_1.parent().id().toString());
        UploadResponse file1_1_1_2 = uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(List.of(file1_1_1_2.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file1_1_1_2.id())
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().method(HttpMethod.POST).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        DocumentInfo info = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file1_1_1_2.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(info);
        Assertions.assertEquals(folderAndFile1_1_1.parent().id(), info.parentId());

        deleteRequest = new DeleteRequest(List.of(folderAndFile1_1_1.parent().id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderAndFile1_1_1.parent().id())
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderAndFile1_1_1.file().id())
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file1_1_1_2.id())
                .exchange()
                .expectStatus().isNotFound();

        deleteRequest = new DeleteRequest(List.of(file1_1_1_2.id()));

        getWebTestClient().method(HttpMethod.POST).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        info = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", file1_1_1_2.id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(info);
        Assertions.assertNull(info.parentId());


        deleteRequest = new DeleteRequest(List.of(folderAndFile2.parent().id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().method(HttpMethod.POST).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/restore")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        info = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderAndFile2.file().id())
                .exchange()
                .expectStatus().isOk()
                .expectBody(DocumentInfo.class)
                .returnResult().getResponseBody();


        Assertions.assertNotNull(info);
        Assertions.assertEquals(folderAndFile2.parent().id(), info.parentId());

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderAndFile2_2.file().id())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", folderAndFile2_2.parent().id())
                .exchange()
                .expectStatus().isOk();

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        var graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        emptyBin(httpGraphQlClient, graphQlRequest);

    }

    @Test
    void whenDeleteFiles_thenVerifySoftDelete() {

        CreateFolderRequest createSourceFolderRequest = new CreateFolderRequest("test-soft-delete-folder", null);

        FolderResponse sourceFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", sourceFolderResponse.id().toString());

        UploadResponse response = uploadDocument(builder);

        DeleteRequest deleteRequest = new DeleteRequest(List.of(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", response.id())
                .exchange()
                .expectStatus().isNotFound();

        HttpGraphQlClient httpGraphQlClient = getGraphQlHttpClient();
        //create post
        ListFolderRequest request = new ListFolderRequest(sourceFolderResponse.id(), null, null, null, null, null, null, null, null, null, null, null
                , null, null, false, null);
        var graphQlRequest = """
                query count($request:ListFolderRequest) {
                    count(request:$request)
                }
                """.trim();

        Mono<ClientGraphQlResponse> countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 1L))
                .expectComplete()
                .verify();

        request = new ListFolderRequest(sourceFolderResponse.id(), null, null, null, null, null, null, null, null, null, null, null
                , null, null, true, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();

        deleteRequest = new DeleteRequest(Collections.singletonList(sourceFolderResponse.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        request = new ListFolderRequest(null, null, null, createSourceFolderRequest.name(), null, null, null, null, null, null, null, null
                , null, null, true, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();

        request = new ListFolderRequest(null, null, null, createSourceFolderRequest.name(), null, null, null, null, null, null, null, null
                , null, null, false, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 1L))
                .expectComplete()
                .verify();

        List<FolderElementInfo> deletedItems = getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(deletedItems);
        Assertions.assertFalse(deletedItems.isEmpty());
        Assertions.assertEquals(sourceFolderResponse.id(), deletedItems.getFirst().id());

        deleteRequest = new DeleteRequest(Collections.singletonList(response.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN)
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        request = new ListFolderRequest(sourceFolderResponse.id(), null, null, null, null, null, null, null, null, null, null, null
                , null, null, false, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();


        emptyBin(httpGraphQlClient, graphQlRequest);

    }

    private void emptyBin(HttpGraphQlClient httpGraphQlClient, String graphQlRequest) {
        Mono<ClientGraphQlResponse> countGraphQl;
        ListFolderRequest request;
        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/empty")
                .exchange()
                .expectStatus().isNoContent();

        request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, null, false, null);

        countGraphQl = httpGraphQlClient
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(countGraphQl)
                .expectNextMatches(doc -> checkCountIsOK(doc, 0L))
                .expectComplete()
                .verify();

        Long count = getWebTestClient().method(HttpMethod.GET).uri(RestApiVersion.API_PREFIX + RestApiVersion.ENDPOINT_RECYCLE_BIN + "/count")
                .exchange()
                .expectStatus().isOk()
                .expectBody(Long.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals(0L, count);
    }


    @Test
    void whenDeleteFolderRecursive_thenOk() {
        CreateFolderRequest createSourceFolderRequest = new CreateFolderRequest("test-delete-folder-source", null);

        FolderResponse sourceFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        CreateFolderRequest createSourceSubFolderRequest = new CreateFolderRequest("test-delete-subfolder-source", sourceFolderResponse.id());

        FolderResponse sourceSubFolderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createSourceSubFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", sourceFolderResponse.id().toString());

        UploadResponse sourceRootFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        builder.part("parentFolderId", sourceSubFolderResponse.id().toString());

        UploadResponse sourceSubFolderFile = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(sourceFolderResponse.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceFolderResponse.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceSubFolderResponse.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceRootFile.id()))
                .exchange()
                .expectStatus().isNotFound();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                        .build(sourceSubFolderFile.id()))
                .exchange()
                .expectStatus().isNotFound();

    }

    @Test
    void whenDeleteFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-delete", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(folderResponse.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", folderResponse.id())
                        .build())
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void whenListFolder_thenOk() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-list", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertEquals("folder-to-list", folderResponse.name());

        List<FolderElementInfo> folders = getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/folders/list")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(FolderElementInfo.class)
                .returnResult().getResponseBody();

        Assertions.assertTrue(folders.stream().anyMatch(f -> f.name().equals("folder-to-list")));

    }

    @Test
    void whenListFolder_thenError() {
        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                    .queryParam("onlyFiles", true)
                    .queryParam("onlyFolders", true)
                    .build())
                .exchange()
                .expectStatus().is4xxClientError();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFiles", true)
                        .build())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("onlyFolders", true)
                        .build())
                .exchange()
                .expectStatus().isOk();

        getWebTestClient().get()
                .uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list")
                        .queryParam("folderId", UUID.randomUUID().toString())
                        .build())
                .exchange()
                .expectStatus().isNotFound();

    }

    @Test
    void whenGetAuditTrail_thenOK() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("folder-to-delete-for-audit", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(folderResponse.id()));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();


        List<AuditLog> auditTrail = getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}")
                        .build(folderResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(2, auditTrail.size());
        Assertions.assertEquals(DELETE_FOLDER, auditTrail.get(0).action());
        Assertions.assertEquals(CREATE_FOLDER, auditTrail.get(1).action());

        auditTrail = getWebTestClient().get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/audit/{id}").queryParam("sort", SortOrder.ASC.name())
                        .build(folderResponse.id()))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(AuditLog.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(auditTrail);
        Assertions.assertEquals(2, auditTrail.size());
        Assertions.assertEquals(DELETE_FOLDER, auditTrail.get(1).action());
        Assertions.assertEquals(CREATE_FOLDER, auditTrail.get(0).action());
    }

    @Test
    void whenUploadFilePreviouslyTrashed_thenBothFilesExist() {
        // Step 1: Create a folder
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-upload-trashed-folder", null);

        FolderResponse folderResponse = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(folderResponse);

        // Step 2: Upload a file in this folder (ID1)
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", folderResponse.id().toString());

        UploadResponse uploadResponse1 = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponse1);
        UUID id1 = uploadResponse1.id();
        log.info("Uploaded file ID1: {}", id1);

        // Get the storage path of ID1 before deletion
        Document document1BeforeDelete = documentRepository.findById(id1).block();
        Assertions.assertNotNull(document1BeforeDelete);
        String storagePath1 = document1BeforeDelete.getStoragePath();
        log.info("Storage path for ID1: {}", storagePath1);

        // Step 3: Delete this file (soft delete)
        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(id1));

        getWebTestClient().method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest))
                .exchange()
                .expectStatus().isNoContent();

        // Verify API returns 404 for the deleted file
        getWebTestClient().get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/info", id1)
                .exchange()
                .expectStatus().isNotFound();

        // Step 4: Verify that this file is still present in the DB with active = false
        Document document1AfterDelete = documentRepository.findByIdAndActive(id1, false).block();
        Assertions.assertNotNull(document1AfterDelete, "Document ID1 should still exist in DB with active=false");
        Assertions.assertFalse(document1AfterDelete.getActive(), "Document ID1 should have active=false");
        Assertions.assertEquals(storagePath1, document1AfterDelete.getStoragePath(), "Storage path should remain unchanged after soft delete");

        // Verify file still exists in storage
        Path storagePath1Full = Path.of(storageBasePath, storagePath1);
        Assertions.assertTrue(Files.exists(storagePath1Full), "File ID1 should still exist in storage after soft delete: " + storagePath1Full);
        log.info("Verified file ID1 still exists in storage: {}", storagePath1Full);

        // Step 5: Upload the same file again in this folder (ID2)
        MultipartBodyBuilder builder2 = newFileBuilder();
        builder2.part("parentFolderId", folderResponse.id().toString());

        UploadResponse uploadResponse2 = getWebTestClient().post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/upload")
                        .queryParam("allowDuplicateFileNames", true)
                        .build())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder2.build()))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponse2);
        UUID id2 = uploadResponse2.id();
        log.info("Uploaded file ID2: {}", id2);

        // Step 6: Verify that the new uploaded file (ID2) exists in the DB with active = true
        Document document2 = documentRepository.findByIdAndActive(id2, true).block();
        Assertions.assertNotNull(document2, "Document ID2 should exist in DB with active=true");
        Assertions.assertTrue(document2.getActive(), "Document ID2 should have active=true");
        String storagePath2 = document2.getStoragePath();
        log.info("Storage path for ID2: {}", storagePath2);

        // Verify file ID2 exists in storage
        Path storagePath2Full = Path.of(storageBasePath, storagePath2);
        Assertions.assertTrue(Files.exists(storagePath2Full), "File ID2 should exist in storage: " + storagePath2Full);
        log.info("Verified file ID2 exists in storage: {}", storagePath2Full);

        // Step 7: Verify that the deleted file (ID1) still exists in the DB with active = false
        Document document1Final = documentRepository.findByIdAndActive(id1, false).block();
        Assertions.assertNotNull(document1Final, "Document ID1 should still exist in DB with active=false after uploading ID2");
        Assertions.assertFalse(document1Final.getActive(), "Document ID1 should still have active=false");

        // Verify file ID1 still exists in storage
        Assertions.assertTrue(Files.exists(storagePath1Full), "File ID1 should still exist in storage after uploading ID2: " + storagePath1Full);
        log.info("Verified file ID1 still exists in storage after uploading ID2: {}", storagePath1Full);

        // Verify both IDs are different
        Assertions.assertNotEquals(id1, id2, "ID1 and ID2 should be different documents");

        // Verify storage paths are different (new file should have its own storage path)
        Assertions.assertNotEquals(storagePath1, storagePath2, "Storage paths should be different for ID1 and ID2");

        log.info("Test completed successfully: ID1={} (active=false), ID2={} (active=true)", id1, id2);
    }


}
