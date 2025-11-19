package org.openfilz.dms.e2e;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.audit.AuditLog;
import org.openfilz.dms.dto.request.*;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.*;

import static org.openfilz.dms.enums.AuditAction.*;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Slf4j
@TestConstructor(autowireMode = ALL)
public class SoftDeleteIT extends TestContainersBaseConfig {

    protected HttpGraphQlClient graphQlHttpClient;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("openfilz.soft-delete.active", () -> true);
    }

    public SoftDeleteIT(WebTestClient webTestClient, Jackson2JsonEncoder customJackson2JsonEncoder) {
        super(webTestClient, customJackson2JsonEncoder);
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

    record RestoreHandler(FolderResponse parent, UploadResponse file) {}

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
        Assertions.assertEquals(folderAndFile1_1_1.parent.id(), info.parentId());

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

    private RestoreHandler createFolderAndFile(String name, UUID parentId) {
        CreateFolderRequest rootFolder1 = new CreateFolderRequest(name, parentId);


        FolderResponse rootFolder1Response = getWebTestClient().post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(rootFolder1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(FolderResponse.class)
                .returnResult().getResponseBody();

        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("parentFolderId", rootFolder1Response.id().toString());

        UploadResponse file1_1 = uploadDocument(builder);

        return new RestoreHandler(rootFolder1Response, file1_1);
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


}
