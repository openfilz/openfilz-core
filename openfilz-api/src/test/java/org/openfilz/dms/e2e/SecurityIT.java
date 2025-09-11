package org.openfilz.dms.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.openfilz.dms.config.RestApiVersion;
import org.openfilz.dms.dto.request.*;
import org.openfilz.dms.dto.response.UploadResponse;
import org.openfilz.dms.enums.SortOrder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.core.io.ClassPathResource;
import org.springframework.graphql.client.ClientGraphQlResponse;
import org.springframework.graphql.client.GraphQlTransportException;
import org.springframework.graphql.client.HttpGraphQlClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.context.TestConstructor.AutowireMode.ALL;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = ALL)
public class SecurityIT extends TestContainersBaseConfig {



    private static String noaccessAccessToken;
    private static String auditAccessToken;
    private static String readerAccessToken;
    private static String contributorAccessToken;
    private static String cleanerAccessToken;
    private static String adminAccessToken;

    public SecurityIT(WebTestClient webTestClient) {
        super(webTestClient);
    }

    @DynamicPropertySource
    static void registerResourceServerIssuerProperty(DynamicPropertyRegistry registry) {
        registry.add("spring.security.no-auth", () -> false);
    }

    @BeforeAll
    static void startContainersAndGetTokens() {
        noaccessAccessToken = getAccessToken("test-user");
        auditAccessToken = getAccessToken("audit-user");
        readerAccessToken = getAccessToken("reader-user");
        contributorAccessToken = getAccessToken("contributor-user");
        cleanerAccessToken = getAccessToken("cleaner-user");
        adminAccessToken = getAccessToken("admin-user");
    }

    protected HttpGraphQlClient newGraphQlClient(String authToken) {
        return newGraphQlClient().mutate().header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken).build();
    }

    @Test
    void testGraphQl_ListFolders() {

        ListFolderRequest request = new ListFolderRequest(null, null, null, null, null, null, null, null, null, null, null, null
                , null, new PageCriteria("name", SortOrder.ASC, 1, 100));
        var graphQlRequest = """
                query listFolder($request:ListFolderRequest) {
                    listFolder(request:$request) {
                      id
                      contentType
                      type
                      name
                      metadata
                    }
                }
                """.trim();

        Mono<ClientGraphQlResponse> response = newGraphQlClient()
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GraphQlTransportException.class);
                    assertThat(((WebClientResponseException) error.getCause()).getStatusCode().value()).isEqualTo(401);
                })
                .verify();

        response = newGraphQlClient(noaccessAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GraphQlTransportException.class);
                    assertThat(((WebClientResponseException) error.getCause()).getStatusCode().value()).isEqualTo(403);
                })
                .verify();

        response = newGraphQlClient(cleanerAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GraphQlTransportException.class);
                    assertThat(((WebClientResponseException) error.getCause()).getStatusCode().value()).isEqualTo(403);
                })
                .verify();

        response = newGraphQlClient(auditAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectErrorSatisfies(error -> {
                    assertThat(error).isInstanceOf(GraphQlTransportException.class);
                    assertThat(((WebClientResponseException) error.getCause()).getStatusCode().value()).isEqualTo(403);
                })
                .verify();

        response = newGraphQlClient(readerAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectNextCount(1)
                .verifyComplete();

        response = newGraphQlClient(contributorAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectNextCount(1)
                .verifyComplete();

        response = newGraphQlClient(adminAccessToken)
                .document(graphQlRequest)
                .variable("request",request)
                .execute();


        StepVerifier.create(response)
                .expectNextCount(1)
                .verifyComplete();

    }

    @Test
    void testUpload() {
        MultipartBodyBuilder builder = newFileBuilder();
        getUploadDocumentExchange(builder,  noaccessAccessToken).expectStatus().isForbidden();
        getUploadDocumentExchange(builder,  contributorAccessToken).expectStatus().isCreated();
        getUploadDocumentExchange(builder,  readerAccessToken).expectStatus().isForbidden();
        getUploadDocumentExchange(builder,  auditAccessToken).expectStatus().isForbidden();
        getUploadDocumentExchange(builder,  cleanerAccessToken).expectStatus().isForbidden();
        getUploadDocumentExchange(builder,  adminAccessToken).expectStatus().isCreated();
        getUploadDocumentExchange(builder).expectStatus().isUnauthorized();
    }

    @Test
    void testUploadMultiple() {
        MultipartBodyBuilder builder = newFileBuilder("schema.sql", "test.txt");

        Map<String, Object> metadata1 = Map.of("helmVersion", "1.0");
        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("schema.sql", new MultipleUploadFileParameterAttributes(null, metadata1));
        Map<String, Object> metadata2 = Map.of("owner", "OpenFilz");
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(null, metadata2));

        getUploadMultipleDocumentExchange(param1, param2, builder, noaccessAccessToken).expectStatus().isForbidden();
        getUploadMultipleDocumentExchange(param1, param2, builder, contributorAccessToken).expectStatus().isOk();
        getUploadMultipleDocumentExchange(param1, param2, builder, readerAccessToken).expectStatus().isForbidden();
        getUploadMultipleDocumentExchange(param1, param2, builder, auditAccessToken).expectStatus().isForbidden();
        getUploadMultipleDocumentExchange(param1, param2, builder, cleanerAccessToken).expectStatus().isForbidden();
        getUploadMultipleDocumentExchange(param1, param2, builder, adminAccessToken).expectStatus().isOk();
        getUploadMultipleDocumentExchange(param1, param2, builder).expectStatus().isUnauthorized();
    }

    @Test
    void testReplaceContent() {
        UploadResponse originalUploadResponse = uploadNewFile();
        Assertions.assertNotNull(originalUploadResponse);
        addAuthorization(getReplaceContentRequest(originalUploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceContentRequest(originalUploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testReplaceMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));

        UploadResponse originalUploadResponse = newFile(builder);

        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getReplaceMeatadataRequest(originalUploadResponse), adminAccessToken).exchange().expectStatus().isOk();

    }

    @Test
    void testPatchMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));
        UploadResponse uploadResponse = newFile(builder);
        addAuthorization(getPatchMetadataRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getPatchMetadataRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getPatchMetadataRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testDeleteMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));
        UploadResponse uploadResponse = newFile(builder);
        addAuthorization(getDeleteMetadataRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isNoContent();
        addAuthorization(getDeleteMetadataRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isNoContent();
    }

    @Test
    void testDownload() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getDownloadRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDownloadRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDownloadRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDownloadRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDownloadRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDownloadRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testSearchIdsByMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        UUID uuid = UUID.randomUUID();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", uuid.toString()));
        UploadResponse uploadResponse = newFile(builder);

        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchIdsByMetadataRequest(uploadResponse, uuid), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testSearchMetadata() {
        MultipartBodyBuilder builder = newFileBuilder();
        builder.part("metadata", Map.of("owner", "OpenFilz", "appId", "MY_APP_1"));
        UploadResponse uploadResponse = newFile(builder);

        addAuthorization(getSearchMetadataRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchMetadataRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getSearchMetadataRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getSearchMetadataRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchMetadataRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getSearchMetadataRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testGetDocumentInfo() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getDocumentInfoRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDocumentInfoRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDocumentInfoRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDocumentInfoRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDocumentInfoRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDocumentInfoRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testMoveFile() {
        UploadResponse uploadResponse = uploadNewFile();

        UploadResponse folder = createFolder();

        addAuthorization(getMoveFileRequest(uploadResponse, folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFileRequest(uploadResponse, createFolder()), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testCopyFile() {
        UploadResponse uploadResponse = uploadNewFile();

        UploadResponse folder = createFolder();

        addAuthorization(getCopyRequest(uploadResponse, folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyRequest(uploadResponse, folder), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getCopyRequest(uploadResponse, folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyRequest(uploadResponse, folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyRequest(uploadResponse, folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyRequest(uploadResponse, createFolder()), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testRenameFile() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getRenameFileRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getRenameFileRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFileRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testDeleteFile() {
        UploadResponse uploadResponse = uploadNewFile();

        addAuthorization(getDeleteFileRequest(uploadResponse), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFileRequest(uploadResponse), cleanerAccessToken).exchange().expectStatus().isNoContent();
        addAuthorization(getDeleteFileRequest(uploadResponse), adminAccessToken).exchange().expectStatus().isNotFound();
    }

    @Test
    void testCreateFolder() {
        addAuthorization(getCreateFolderRequest(), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCreateFolderRequest(), contributorAccessToken).exchange().expectStatus().isCreated();
        addAuthorization(getCreateFolderRequest(), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCreateFolderRequest(), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCreateFolderRequest(), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCreateFolderRequest(), adminAccessToken).exchange().expectStatus().isCreated();
    }

    @Test
    void testMoveFolder() {

        UploadResponse folder1 = createFolder();
        UploadResponse folder2 = createFolder();

        addAuthorization(getMoveFolderRequest(folder1, folder2), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getMoveFolderRequest(folder1, folder2), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, folder2), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getMoveFolderRequest(folder1, createFolder()), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testCopyFolder() {

        UploadResponse folder1 = createFolder();
        UploadResponse folder2 = createFolder();

        addAuthorization(getCopyFolderRequest(folder1, folder2), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyFolderRequest(folder1, folder2), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getCopyFolderRequest(folder1, folder2), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyFolderRequest(folder1, folder2), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyFolderRequest(folder1, folder2), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getCopyFolderRequest(folder1, createFolder()), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testRenameFolder() {

        UploadResponse folder = createFolder();

        addAuthorization(getRenameFolderRequest(folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getRenameFolderRequest(folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getRenameFolderRequest(folder), adminAccessToken).exchange().expectStatus().isOk();
    }

    @Test
    void testDeleteFolder() {

        UploadResponse folder = createFolder();

        addAuthorization(getDeleteFolderRequest(folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), contributorAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), readerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDeleteFolderRequest(folder), cleanerAccessToken).exchange().expectStatus().isNoContent();
        addAuthorization(getDeleteFolderRequest(createFolder()), adminAccessToken).exchange().expectStatus().isNoContent();
    }

    @Test
    void testListFolder() {

        UploadResponse folder = createFolder();

        uploadNewFile(folder.id());

        addAuthorization(getListFolderRequest(folder), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getListFolderRequest(folder), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getListFolderRequest(folder), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getListFolderRequest(folder), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getListFolderRequest(folder), cleanerAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getListFolderRequest(folder), adminAccessToken).exchange().expectStatus().isOk();
    }

    private WebTestClient.RequestHeadersSpec<?> getListFolderRequest(UploadResponse folder) {
        return webTestClient.get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/folders/list").queryParam("folderId", folder.id()).build());
    }

    private WebTestClient.RequestHeadersSpec<?> getDeleteFolderRequest(UploadResponse folder) {
        DeleteRequest deleteRequest = new DeleteRequest(Collections.singletonList(folder.id()));

        return webTestClient.method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(deleteRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getRenameFolderRequest(UploadResponse folder) {
        RenameRequest renameRequest = new RenameRequest("renamed-folder-" + UUID.randomUUID());

        return webTestClient.put().uri(RestApiVersion.API_PREFIX + "/folders/{folderId}/rename", folder.id())
                .body(BodyInserters.fromValue(renameRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getCopyFolderRequest(UploadResponse folder1, UploadResponse folder2) {
        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(folder1.id()), folder2.id(), false);

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders/copy")
                .body(BodyInserters.fromValue(copyRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getMoveFolderRequest(UploadResponse folder1, UploadResponse folder2) {
        MoveRequest moveRequest = new MoveRequest(Collections.singletonList(folder1.id()), folder2.id(), false);

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders/move")
                .body(BodyInserters.fromValue(moveRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getCreateFolderRequest() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder" + UUID.randomUUID(), null);

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders")
                .body(BodyInserters.fromValue(createFolderRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getDeleteFileRequest(UploadResponse uploadResponse) {
        DeleteRequest deleteRequest = new DeleteRequest(List.of(uploadResponse.id()));

        return webTestClient.method(HttpMethod.DELETE).uri(RestApiVersion.API_PREFIX + "/files")
                .body(BodyInserters.fromValue(deleteRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getRenameFileRequest(UploadResponse uploadResponse) {
        RenameRequest renameRequest = new RenameRequest("new-name" + UUID.randomUUID() + ".sql");

        return webTestClient.put().uri(RestApiVersion.API_PREFIX + "/files/{fileId}/rename", uploadResponse.id())
                .body(BodyInserters.fromValue(renameRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getCopyRequest(UploadResponse uploadResponse, UploadResponse folder) {
        CopyRequest copyRequest = new CopyRequest(Collections.singletonList(uploadResponse.id()), folder.id(), false);

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/files/copy")
                .body(BodyInserters.fromValue(copyRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getMoveFileRequest(UploadResponse uploadResponse, UploadResponse folder) {
        MoveRequest moveRequest = new MoveRequest(Collections.singletonList(uploadResponse.id()), folder.id(), false);

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/files/move")
                .body(BodyInserters.fromValue(moveRequest));
    }

    private UploadResponse createFolder() {
        CreateFolderRequest createFolderRequest = new CreateFolderRequest("test-folder-" + UUID.randomUUID(), null);

        return addAuthorization(webTestClient.post().uri(RestApiVersion.API_PREFIX + "/folders").body(BodyInserters.fromValue(createFolderRequest)), contributorAccessToken)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }

    private WebTestClient.RequestHeadersSpec<?> getDocumentInfoRequest(UploadResponse uploadResponse) {
        return webTestClient.get().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/info")
                .build(uploadResponse.id()));
    }

    private WebTestClient.RequestBodySpec getSearchMetadataRequest(UploadResponse uploadResponse) {
        Assertions.assertNotNull(uploadResponse);

        return webTestClient.post().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/search/metadata")
                .build(uploadResponse.id()));
    }

    private WebTestClient.RequestHeadersSpec<?> getSearchIdsByMetadataRequest(UploadResponse uploadResponse, UUID uuid) {
        Assertions.assertNotNull(uploadResponse);

        SearchByMetadataRequest searchByMetadataRequest = new SearchByMetadataRequest(null, null, null, null, Map.of("appId", uuid.toString()));

        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/documents/search/ids-by-metadata")
                .body(BodyInserters.fromValue(searchByMetadataRequest));
    }

    @Test
    void testDownloadMultiple() {
        MultipartBodyBuilder builder = newFileBuilder("schema.sql", "test.txt");
        Map<String, Object> metadata1 = Map.of("helmVersion", "1.0");
        MultipleUploadFileParameter param1 = new MultipleUploadFileParameter("schema.sql", new MultipleUploadFileParameterAttributes(null, metadata1));
        Map<String, Object> metadata2 = Map.of("owner", "OpenFilz");
        MultipleUploadFileParameter param2 = new MultipleUploadFileParameter("test.txt", new MultipleUploadFileParameterAttributes(null, metadata2));

        List<UploadResponse> uploadResponses = getUploadMultipleDocumentExchange(param1, param2, builder, contributorAccessToken).expectStatus().isOk().expectBody(new ParameterizedTypeReference<List<UploadResponse>>() {
                })
                .returnResult().getResponseBody();

        Assertions.assertNotNull(uploadResponses);
        UploadResponse uploadResponse1 = uploadResponses.get(0);
        UploadResponse uploadResponse2 = uploadResponses.get(1);

        addAuthorization(getDownloadMultipleRequest(uploadResponse1, uploadResponse2), noaccessAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDownloadMultipleRequest(uploadResponse1, uploadResponse2), contributorAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDownloadMultipleRequest(uploadResponse1, uploadResponse2), readerAccessToken).exchange().expectStatus().isOk();
        addAuthorization(getDownloadMultipleRequest(uploadResponse1, uploadResponse2), auditAccessToken).exchange().expectStatus().isForbidden();
        addAuthorization(getDownloadMultipleRequest(uploadResponse1, uploadResponse2), adminAccessToken).exchange().expectStatus().isOk();
    }

    private WebTestClient.RequestHeadersSpec<?> getDownloadMultipleRequest(UploadResponse uploadResponse1, UploadResponse uploadResponse2) {
        return webTestClient.post().uri(RestApiVersion.API_PREFIX + "/documents/download-multiple")
                .body(BodyInserters.fromValue(List.of(uploadResponse1.id(), uploadResponse2.id())));
    }

    private WebTestClient.RequestHeadersSpec<?> getDownloadRequest(UploadResponse uploadResponse) {
        return webTestClient.get().uri(RestApiVersion.API_PREFIX + "/documents/{id}/download", uploadResponse.id());
    }


    private WebTestClient.RequestHeadersSpec<?> getDeleteMetadataRequest(UploadResponse uploadResponse) {
        Assertions.assertNotNull(uploadResponse);

        DeleteMetadataRequest deleteRequest = new DeleteMetadataRequest(Collections.singletonList("owner"));

        return webTestClient.method(HttpMethod.DELETE).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(uploadResponse.id()))
                .body(BodyInserters.fromValue(deleteRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getPatchMetadataRequest(UploadResponse uploadResponse) {
        Assertions.assertNotNull(uploadResponse);

        UpdateMetadataRequest updateMetadataRequest = new UpdateMetadataRequest(Map.of("owner", "Joe", "appId",  "MY_APP_2"));

        return webTestClient.method(HttpMethod.PATCH).uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/metadata").build(uploadResponse.id()))
                .body(BodyInserters.fromValue(updateMetadataRequest));
    }

    private WebTestClient.RequestHeadersSpec<?> getReplaceMeatadataRequest(UploadResponse originalUploadResponse) {
        Assertions.assertNotNull(originalUploadResponse);
        UUID id = originalUploadResponse.id();
        Long originalSize = originalUploadResponse.size();
        Assertions.assertTrue(originalSize != null   && originalSize > 0);
        Map<String, Object> newMetadata = Map.of("owner", "Google", "clientId", "Joe");
        return webTestClient.put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-metadata")
                        .build(id.toString()))
                .body(BodyInserters.fromValue(newMetadata));
    }

    private WebTestClient.RequestHeadersSpec<?> getReplaceContentRequest(UploadResponse originalUploadResponse) {
        UUID id = originalUploadResponse.id();
        Long originalSize = originalUploadResponse.size();
        Assertions.assertTrue(originalSize != null   && originalSize > 0);
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        builder.part("file", new ClassPathResource("test.txt"));
        return webTestClient.put().uri(uri -> uri.path(RestApiVersion.API_PREFIX + "/documents/{id}/replace-content")
                        .build(id.toString()))
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()));
    }

    private UploadResponse uploadNewFile() {
        return uploadNewFile(null);
    }

    private UploadResponse uploadNewFile(UUID parentFolderId) {
        MultipartBodyBuilder builder = newFileBuilder();
        if(parentFolderId != null) {
            builder.part("parentFolderId", parentFolderId.toString());
        }
        return newFile(builder);
    }

    private UploadResponse newFile(MultipartBodyBuilder builder) {
        return getUploadDocumentExchange(builder,  contributorAccessToken)
                .expectStatus().isCreated()
                .expectBody(UploadResponse.class)
                .returnResult().getResponseBody();
    }


}
